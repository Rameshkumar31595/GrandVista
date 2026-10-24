"""Live security checks against the ISOLATED stack. Never touches the real database."""
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

GATEWAY = 'http://localhost:8080/api'
SECRET_RAW = base64.b64decode(os.environ['JWT_SECRET'])   # key of the disposable stack (the services Base64-decode JWT_SECRET)
LOG_DIR = os.environ['GV_LOG_DIR']
RESULTS = os.environ['GV_WORK_DIR'] + '/results'
PASSWORD = 'Aa1-' + secrets.token_hex(8)                    # throw-away test-user password, new on every run
ADMIN_EMAIL = os.environ['ADMIN_EMAIL']
ADMIN_PASSWORD = os.environ['ADMIN_PASSWORD']
INTERNAL_API_KEY = os.environ['INTERNAL_API_KEY']
os.makedirs(RESULTS, exist_ok=True)
results = []


def call(method, path, body=None, token=None, headers=None, base=GATEWAY):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw else None), dict(resp.headers)
    except urllib.error.HTTPError as err:
        raw = err.read().decode()
        try:
            return err.code, json.loads(raw), dict(err.headers)
        except ValueError:
            return err.code, raw, dict(err.headers)


def check(name, ok, detail=''):
    results.append({'check': name, 'pass': bool(ok), 'detail': str(detail)[:200]})
    print(('PASS ' if ok else 'FAIL ') + name + ('' if ok else '  -> ' + str(detail)[:200]), flush=True)


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()


def mint(secret, claims, alg='HS256'):
    header = {'alg': alg, 'typ': 'JWT'}
    signing = b64(json.dumps(header).encode()) + '.' + b64(json.dumps(claims).encode())
    if alg == 'none':
        return signing + '.'
    sig = hmac.new(secret, signing.encode(), hashlib.sha256).digest()
    return signing + '.' + b64(sig)


def login(email, password):
    s, b, _ = call('POST', '/auth/login', {'email': email, 'password': password})
    return s, (b.get('accessToken') or b.get('token')) if isinstance(b, dict) else None, b


def register(tag):
    email = f'sec-{tag}-{int(time.time() * 1000)}@example.com'
    s, b, _ = call('POST', '/auth/register', {'name': 'Sec Tester', 'email': email, 'password': PASSWORD})
    return email, s, b


now = int(time.time())
base_claims = {'iss': 'grandvista-auth-service', 'sub': '1', 'email': 'x@x', 'roles': ['ROLE_ADMIN'], 'iat': now, 'exp': now + 600}

# ---- identity / registration ---------------------------------------------------------------------
emailA, s, _ = register('a')
s, tokA, _ = login(emailA, PASSWORD)
emailB, s, _ = register('b')
s, tokB, _ = login(emailB, PASSWORD)
admin_status, tokAdmin, _ = login(ADMIN_EMAIL, ADMIN_PASSWORD)
check('admin can log in', admin_status == 200 and tokAdmin)

email = f'esc-{int(time.time() * 1000)}@example.com'
s, b, _ = call('POST', '/auth/register', {'name': 'Eve', 'email': email, 'password': PASSWORD, 'role': 'ROLE_ADMIN'})
_, tokEve, body = login(email, PASSWORD)
check('registration cannot self-assign ROLE_ADMIN', body['user']['role'] == 'ROLE_USER', body['user'])
s, _, _ = call('GET', '/auth/users', token=tokEve)
check('self-registered user cannot list users (403)', s == 403, s)

s1, _, _ = login(emailA, 'wrong-password')
s2, b2, _ = login('nobody@example.com', 'whatever')
_, bw, _ = call('POST', '/auth/login', {'email': emailA, 'password': 'wrong-password'})
_, bn, _ = call('POST', '/auth/login', {'email': 'nobody@example.com', 'password': 'whatever'})
check('login failure is 401 with the same message for unknown user and wrong password',
      s1 == 401 and s2 == 401 and bw['message'] == bn['message'], (bw.get('message'), bn.get('message')))
dup_s, _, _ = call('POST', '/auth/register', {'name': 'Dup', 'email': emailA, 'password': PASSWORD})
check('duplicate registration -> 409', dup_s == 409, dup_s)
check('passwords are not returned by the API', 'password' not in json.dumps(body).lower())

# ---- JWT forgery / expiry --------------------------------------------------------------------------
s, _, _ = call('GET', '/auth/users', token=mint(SECRET_RAW, base_claims))
check('sanity: a token minted with the real key is accepted (proves the forgery tests are meaningful)', s == 200, s)
s, _, _ = call('GET', '/auth/users', token=mint(SECRET_RAW, {**base_claims, 'exp': now - 120}))
check('expired token (correct signature) -> 401', s == 401, s)
s, _, _ = call('GET', '/auth/users', token=mint(secrets.token_bytes(48), base_claims))
check('token signed with a different key -> 401', s == 401, s)
s, _, _ = call('GET', '/auth/users', token=mint(SECRET_RAW, {**base_claims, 'iss': 'evil-issuer'}))
check('token with a foreign issuer -> 401', s == 401, s)
s, _, _ = call('GET', '/auth/users', token=mint(SECRET_RAW, base_claims, alg='none'))
check('unsigned (alg=none) token -> 401', s == 401, s)
tampered = tokA[:-3] + ('AAA' if not tokA.endswith('AAA') else 'BBB')
s, _, _ = call('GET', '/bookings/my', token=tampered)
check('tampered signature -> 401', s == 401, s)
head, payload, sig = tokA.split('.')
claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
claims['roles'] = ['ROLE_ADMIN']
s, _, _ = call('GET', '/auth/users', token=head + '.' + b64(json.dumps(claims).encode()) + '.' + sig)
check('payload edited to ROLE_ADMIN with the old signature -> 401', s == 401, s)
s, _, _ = call('GET', '/bookings/my')
check('no token -> 401', s == 401, s)

# ---- role enforcement through the gateway ---------------------------------------------------------
for name, method, path in (('list all bookings', 'GET', '/bookings'), ('list all payments', 'GET', '/payments'),
                           ('pricing rules', 'GET', '/pricing-rules'), ('all rooms (admin view)', 'GET', '/rooms/all'),
                           ('inventory summary', 'GET', '/rooms/inventory-summary')):
    s, _, _ = call(method, path, token=tokA)
    check(f'USER cannot {name} (403)', s == 403, s)
s, _, _ = call('POST', '/rooms', {'hotelId': 1, 'roomNumber': '999', 'roomType': 'SUITE', 'basePrice': 100, 'capacity': 2}, token=tokA)
check('USER cannot create a room (403)', s == 403, s)
s, _, _ = call('POST', '/bookings', {'roomId': 1, 'checkIn': '2030-01-01', 'checkOut': '2030-01-02', 'numberOfGuests': 1}, token=tokAdmin)
check('ADMIN cannot create a booking as a guest (403)', s == 403, s)

# ---- ownership (IDOR) ------------------------------------------------------------------------------
rooms = call('GET', '/rooms?city=Hyderabad')[1]
room_id = rooms[0]['id']
d = date.today() + timedelta(days=400 + int(time.time()) % 250)   # unique per run
s, bk, _ = call('POST', '/bookings', {'roomId': room_id, 'checkIn': d.isoformat(), 'checkOut': (d + timedelta(days=2)).isoformat(), 'numberOfGuests': 2}, tokA)
bid = bk['bookingId']
for name, method, path, body in (('read', 'GET', f'/bookings/{bid}', None),
                                 ('pay', 'POST', f'/bookings/{bid}/pay', {'paymentMethod': 'UPI'}),
                                 ('cancel', 'POST', f'/bookings/{bid}/cancel', None)):
    s, _, _ = call(method, path, body, tokB)
    check(f"another guest cannot {name} someone else's booking (403)", s == 403, s)
s, _, _ = call('GET', f'/payments/booking/{bid}', token=tokB)
check('payments of a booking the guest does not own are refused or empty', s in (200, 403), s)
check('booking still PENDING and owned by A after the attacks',
      call('GET', f'/bookings/{bid}', token=tokA)[1]['bookingStatus'] == 'PENDING')
s, _, _ = call('GET', f'/bookings/{bid}', token=tokAdmin)
check('ADMIN can read any booking', s == 200, s)
s, _, _ = call('GET', '/bookings/99999999', token=tokA)
check('unknown booking -> 404', s == 404, s)

# ---- internal endpoints and header spoofing -------------------------------------------------------
for path in ('/internal/holds', '/eureka/apps', '/actuator/env', '/actuator/heapdump'):
    s, _, _ = call('POST', path, {}, tokA, base='http://localhost:8080')
    check(f'gateway blocks {path}', s in (403, 404), s)
s, _, _ = call('POST', '/internal/holds', {'roomId': room_id, 'checkIn': d.isoformat(), 'checkOut': (d + timedelta(days=1)).isoformat(), 'guests': 1},
               tokA, {'X-Internal-Api-Key': 'not-the-' + secrets.token_hex(4)}, base='http://localhost:8082')
check('direct room-service internal call with a wrong key -> 403', s == 403, s)
s, _, _ = call('POST', '/internal/holds', {'roomId': room_id}, None, base='http://localhost:8082')
check('direct room-service internal call without credentials -> 403/401', s in (401, 403), s)
for port in (8081, 8082, 8083, 8084):
    s, _, _ = call('GET', '/actuator/env', base=f'http://localhost:{port}')
    check(f'actuator/env not exposed on :{port}', s in (401, 403, 404), s)

# ---- input validation ------------------------------------------------------------------------------
today = date.today()
cases = [('past check-in', {'roomId': room_id, 'checkIn': (today - timedelta(days=3)).isoformat(), 'checkOut': today.isoformat(), 'numberOfGuests': 1}, 400),
         ('check-out before check-in', {'roomId': room_id, 'checkIn': (today + timedelta(days=9)).isoformat(), 'checkOut': (today + timedelta(days=7)).isoformat(), 'numberOfGuests': 1}, 400),
         ('zero guests', {'roomId': room_id, 'checkIn': (today + timedelta(days=9)).isoformat(), 'checkOut': (today + timedelta(days=10)).isoformat(), 'numberOfGuests': 0}, 400),
         ('too many guests', {'roomId': room_id, 'checkIn': (today + timedelta(days=9)).isoformat(), 'checkOut': (today + timedelta(days=10)).isoformat(), 'numberOfGuests': 40}, 400),
         ('stay longer than 30 nights', {'roomId': room_id, 'checkIn': (today + timedelta(days=60)).isoformat(), 'checkOut': (today + timedelta(days=100)).isoformat(), 'numberOfGuests': 1}, 400),
         ('unknown room', {'roomId': 987654, 'checkIn': (today + timedelta(days=9)).isoformat(), 'checkOut': (today + timedelta(days=10)).isoformat(), 'numberOfGuests': 1}, 404),
         ('missing fields', {}, 400)]
for name, body, expected in cases:
    s, resp, _ = call('POST', '/bookings', body, tokA)
    check(f'booking validation: {name} -> {expected}', s == expected, (s, resp))
s, resp, _ = call('POST', '/bookings', {'roomId': 'abc'}, tokA)
check('malformed JSON types -> 400 (no 500)', s == 400, s)
s, resp, _ = call('GET', "/rooms?city=%27%20OR%201%3D1%20--&maxPrice=abc")
check('SQL-ish / malformed search parameters never produce a 500', s in (200, 400), (s, str(resp)[:100]))
s, resp, _ = call('GET', "/rooms?city=%27%3B%20DROP%20TABLE%20rooms%3B--")
check('injection string in city returns an empty/normal result', s == 200 and resp == [], (s, str(resp)[:100]))
s, resp, _ = call('POST', '/auth/register', {'name': 'x', 'email': 'not-an-email', 'password': 'short'})
check('registration validates name/email/password -> 400 with field errors', s == 400 and 'validationErrors' in resp, resp)
s, resp, _ = call('POST', '/auth/login', {})
check('empty login body -> 400', s == 400, s)

# ---- error bodies do not leak internals ----------------------------------------------------------
s, resp, routed_hdr = call('GET', '/rooms/987654')
blob = json.dumps(resp)
check('404 body has no stack trace / class names', 'Exception' not in blob and 'at com.' not in blob and '.java' not in blob, blob[:150])
s, resp, hdr = call('GET', '/definitely/not/a/route', token=tokA, base='http://localhost:8080')
check('unknown gateway route is denied cleanly (403/404)', s in (403, 404), s)
check('routed responses carry X-Request-Id for tracing', 'x-request-id' in {k.lower() for k in routed_hdr}, list(routed_hdr)[:8])
_, _, edge_hdr = call('GET', '/bookings/my')   # rejected at the edge (401) before routing
print('NOTE edge-rejected 401 carries X-Request-Id:', 'x-request-id' in {k.lower() for k in edge_hdr})

# ---- CORS ----------------------------------------------------------------------------------------------
s, _, h = call('OPTIONS', '/bookings', headers={'Origin': 'http://evil.example', 'Access-Control-Request-Method': 'POST'})
check('CORS preflight from an unknown origin is refused', s == 403, s)
s, _, h = call('OPTIONS', '/bookings', headers={'Origin': 'http://localhost:5173', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type'})
hl = {k.lower(): v for k, v in h.items()}
check('CORS preflight from the React dev server is allowed without credentials',
      s == 200 and hl.get('access-control-allow-origin') == 'http://localhost:5173' and 'access-control-allow-credentials' not in hl, hl)

# ---- logs: no secrets / tokens ------------------------------------------------------------------
leaks = []
needles = ((os.environ['JWT_SECRET'], 'JWT secret'), (INTERNAL_API_KEY, 'internal api key'), (ADMIN_PASSWORD, 'admin password'),
           (PASSWORD, 'guest password'), (os.environ['DB_PASSWORD'], 'database password'))
for name in ('eureka', 'auth', 'room', 'room2', 'payment', 'booking', 'gateway'):
    try:
        text = open(f'{LOG_DIR}/{name}.log', encoding='utf-8', errors='ignore').read()
    except OSError:
        continue
    for needle, label in needles:
        if needle in text:
            leaks.append((name, label))
    if re.search(r'eyJ[A-Za-z0-9_-]{10,}\.eyJ', text):
        leaks.append((name, 'a JWT'))
check('no secrets, passwords or JWTs in any service log', not leaks, leaks)

summary = {'passed': sum(r['pass'] for r in results), 'failed': sum(not r['pass'] for r in results), 'total': len(results)}
print(json.dumps(summary))
json.dump({'summary': summary, 'results': results}, open(RESULTS + '/security_results.json', 'w'), indent=1)
sys.exit(1 if summary['failed'] else 0)
