"""End-to-end checks of a CLOUD-style deployment, through the public API Gateway only.

Works against the local simulation (docker/cloud/sim) and against the real Render URLs. Uses only synthetic data:
throw-away users (e-mail ...@example.com), bookings far in the future, simulated payments. Never point it at the
Windows PostgreSQL stack.

Environment:
  GV_GATEWAY          gateway base URL, e.g. https://grandvista-gateway.onrender.com     (required)
  GV_DEMO_CODE        reviewer access code                                               (required)
  GV_EUREKA           Eureka base URL (optional)    GV_EUREKA_PASSWORD   (optional, user "eureka")
  GV_FRONTEND         frontend URL (optional) - also used as the CORS origin
  GV_SERVICE_URLS     comma separated direct service URLs (optional) - checks /internal/** is not open
  GV_TEST_RATE_LIMIT  1 = also trigger the wrong-code rate limit (uses a fake client address)   default 1
  GV_RESULTS          output JSON file (optional)
Prints one PASS/FAIL line per check and a JSON summary; exit code 1 if any check failed.
"""
import base64
import json
import os
import secrets
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

GATEWAY = os.environ['GV_GATEWAY'].rstrip('/')
API = GATEWAY + '/api'
CODE = os.environ['GV_DEMO_CODE']
EUREKA = os.environ.get('GV_EUREKA', '').rstrip('/')
EUREKA_PASSWORD = os.environ.get('GV_EUREKA_PASSWORD', '')
FRONTEND = os.environ.get('GV_FRONTEND', '').rstrip('/')
SERVICE_URLS = [u.strip().rstrip('/') for u in os.environ.get('GV_SERVICE_URLS', '').split(',') if u.strip()]
TEST_RATE_LIMIT = os.environ.get('GV_TEST_RATE_LIMIT', '1') == '1'
PASSWORD = 'Aa1-' + secrets.token_hex(8)
RUN = secrets.token_hex(3)
results = []


def call(method, url, body=None, token=None, headers=None, timeout=90):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    req.add_header('Accept', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            try:
                return resp.status, (json.loads(raw) if raw else None), dict(resp.headers)
            except ValueError:
                return resp.status, raw, dict(resp.headers)
    except urllib.error.HTTPError as err:
        raw = err.read().decode(errors='replace')
        try:
            return err.code, json.loads(raw), dict(err.headers)
        except ValueError:
            return err.code, raw, dict(err.headers)
    except Exception as err:  # connection refused / timeout
        return 0, repr(err), {}


def check(name, condition, detail=''):
    results.append({'check': name, 'pass': bool(condition), 'detail': str(detail)[:300]})
    print(('PASS ' if condition else 'FAIL ') + name + ('' if condition else '  -> ' + str(detail)[:300]), flush=True)


def jwt_roles(token):
    payload = token.split('.')[1]
    payload += '=' * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))['roles']


def demo_login(account, code=CODE, client=None):
    headers = {'X-Demo-Access-Code': code} if code else {}
    if client:
        headers['X-Forwarded-For'] = client
    return call('POST', API + '/auth/demo/login', {'accountId': account}, headers=headers)


def register_and_login(tag):
    email = f'cloud-{tag}-{RUN}-{int(time.time() * 1000)}@example.com'
    s, b, _ = call('POST', API + '/auth/register', {'name': 'Cloud Tester', 'email': email, 'password': PASSWORD})
    s2, b2, _ = call('POST', API + '/auth/login', {'email': email, 'password': PASSWORD})
    return s, s2, (b2 or {}).get('accessToken'), email


def book(token, room_id, check_in, nights=2):
    return call('POST', API + '/bookings', {'roomId': room_id, 'checkIn': check_in.isoformat(),
                                            'checkOut': (check_in + timedelta(days=nights)).isoformat(),
                                            'numberOfGuests': 1}, token)


def wait_until_ready(timeout=600):
    """Free instances may be asleep: wait until the gateway answers and routes to the room service."""
    end = time.time() + timeout
    last = None
    while time.time() < end:
        s, b, _ = call('GET', API + '/hotels', timeout=60)
        s_auth = call('GET', API + '/auth/demo/accounts', timeout=60)[0]     # 401/403 = routed to auth-service
        if s == 200 and isinstance(b, list) and s_auth in (200, 401, 403):
            return True, s
        last = (s, str(b)[:120])
        time.sleep(10)
    return False, last


# ================================================================================================ infrastructure
ok, info = wait_until_ready()
check('INFRA gateway is up and routes /api/hotels to room-service (via Eureka)', ok, info)
s, b, _ = call('GET', GATEWAY + '/actuator/health')
check('INFRA gateway /actuator/health is UP', s == 200 and isinstance(b, dict) and b.get('status') == 'UP', (s, b))

if EUREKA:
    s, b, _ = call('GET', EUREKA + '/actuator/health')
    check('INFRA eureka /actuator/health is UP (open for the platform health check)', s == 200, (s, b))
    s, b, _ = call('GET', EUREKA + '/eureka/apps')
    check('SEC eureka registry refuses anonymous access (401)', s == 401, s)
    s, b, _ = call('POST', EUREKA + '/eureka/apps/ROOM-SERVICE', {'instance': {}})
    check('SEC anonymous registration of a fake instance is refused (401)', s == 401, s)
    if EUREKA_PASSWORD:
        auth = 'Basic ' + base64.b64encode(f'eureka:{EUREKA_PASSWORD}'.encode()).decode()
        apps = set()
        end = time.time() + 300
        while time.time() < end:
            s, b, _ = call('GET', EUREKA + '/eureka/apps', headers={'Authorization': auth})
            if s == 200 and isinstance(b, dict):
                apps = {a['name']: [i['status'] for i in (a['instance'] if isinstance(a['instance'], list) else [a['instance']])]
                        for a in (b['applications']['application'] if isinstance(b['applications']['application'], list)
                                  else [b['applications']['application']])}
                if {'API-GATEWAY', 'AUTH-SERVICE', 'ROOM-SERVICE', 'BOOKING-SERVICE', 'PAYMENT-SERVICE'} <= set(apps):
                    break
            time.sleep(10)
        check('INFRA eureka lists API-GATEWAY, AUTH, ROOM, BOOKING, PAYMENT as UP',
              all(apps.get(n) and 'UP' in apps[n] for n in
                  ('API-GATEWAY', 'AUTH-SERVICE', 'ROOM-SERVICE', 'BOOKING-SERVICE', 'PAYMENT-SERVICE')), apps)

if FRONTEND:
    s, b, _ = call('GET', FRONTEND + '/')
    check('WEB frontend index.html is served', s == 200 and 'GrandVista' in str(b) or '<div id="root"' in str(b), s)
    s, b, _ = call('GET', FRONTEND + '/my-bookings')
    check('WEB SPA deep link falls back to index.html', s == 200, s)
    s, _, h = call('OPTIONS', API + '/auth/demo/login', headers={
        'Origin': FRONTEND, 'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'content-type,x-demo-access-code'})
    check('WEB CORS preflight from the frontend origin allows the access-code header',
          s == 200 and h.get('Access-Control-Allow-Origin') == FRONTEND, (s, h.get('Access-Control-Allow-Origin')))
    s, _, h = call('OPTIONS', API + '/bookings', headers={'Origin': 'https://evil.example.com',
                                                          'Access-Control-Request-Method': 'POST'})
    check('SEC CORS preflight from a foreign origin is refused', s == 403, s)

for url in SERVICE_URLS:
    s, _, _ = call('POST', url + '/internal/holds', {'roomId': 1})
    check(f'SEC {url} /internal/** without the internal key is refused', s in (401, 403), s)
    s, _, _ = call('POST', url + '/internal/holds', {'roomId': 1}, headers={'X-Internal-Api-Key': 'guess'})
    check(f'SEC {url} /internal/** with a wrong key is refused', s in (401, 403), s)

# ================================================================================================ demo login
s, b, _ = call('GET', API + '/auth/demo/accounts')
check('DEMO account list without the access code -> 401', s == 401, (s, b))
for account in ('ramesh', 'guest2', 'manager'):
    s, b, _ = demo_login(account, code=None)
    check(f'DEMO {account} without access code -> 401 and no token', s == 401 and 'accessToken' not in str(b), s)
s, b, _ = demo_login('manager', code='wrong-' + CODE[::-1])
check('DEMO manager with a WRONG access code -> 401 and no token', s == 401 and 'accessToken' not in str(b), s)

s, b, _ = call('GET', API + '/auth/demo/accounts', headers={'X-Demo-Access-Code': CODE})
names = [a.get('displayName') for a in b] if isinstance(b, list) else b
check('DEMO account list with the access code: Ramesh, Demo Guest 2, GrandVista Manager',
      s == 200 and names == ['Ramesh', 'Demo Guest 2', 'GrandVista Manager'], (s, names))
tokens = {}
for account, role in (('ramesh', 'ROLE_USER'), ('guest2', 'ROLE_USER'), ('manager', 'ROLE_ADMIN')):
    s, b, _ = demo_login(account)
    tokens[account] = (b or {}).get('accessToken') if isinstance(b, dict) else None
    check(f'DEMO {account} signs in with the access code and gets {role}',
          s == 200 and tokens[account] and jwt_roles(tokens[account]) == [role], (s, str(b)[:120]))
ramesh, guest2, manager = tokens['ramesh'], tokens['guest2'], tokens['manager']

if TEST_RATE_LIMIT:
    fake = f'198.51.100.{secrets.randbelow(250) + 1}'
    statuses = [demo_login('manager', code=f'guess-{i}-{RUN}', client=fake)[0] for i in range(5)]
    s, b, h = demo_login('manager', client=fake)
    check('DEMO 5 wrong codes from one client -> the 6th attempt (even with the right code) is 429',
          statuses == [401] * 5 and s == 429 and 'Retry-After' in h, (statuses, s))

# ================================================================================================ normal auth + roles
s_reg, s_login, user_token, email = register_and_login('user')
check('AUTH normal registration -> 201', s_reg == 201, s_reg)
check('AUTH normal login -> 200 with a ROLE_USER JWT', s_login == 200 and jwt_roles(user_token) == ['ROLE_USER'], s_login)
s, _, _ = call('POST', API + '/auth/login', {'email': email, 'password': 'Wrong-pass-1'})
check('AUTH wrong password -> 401', s == 401, s)
s, _, _ = call('POST', API + '/auth/register', {'name': 'Mallory Test', 'email': f'x{RUN}@demo.grandvista.local', 'password': PASSWORD})
check('AUTH registering the reserved demo domain -> 409', s == 409, s)
s, _, _ = call('GET', API + '/bookings/my')
check('AUTH protected endpoint without a token -> 401', s == 401, s)
s, _, _ = call('GET', API + '/bookings/my', token=ramesh[:-4] + 'AAAA')
check('AUTH tampered JWT signature -> 401', s == 401, s)

for path in ('/auth/users', '/bookings', '/payments', '/pricing-rules', '/rooms/all'):
    s1, _, _ = call('GET', API + path, token=ramesh)
    s2, _, _ = call('GET', API + path, token=manager)
    check(f'ROLE {path}: Ramesh (USER) 403, GrandVista Manager (ADMIN) 200', s1 == 403 and s2 == 200, (s1, s2))

s, users, _ = call('GET', API + '/auth/users', token=manager)
target = next((u for u in users if u.get('email') == email), None) if isinstance(users, list) else None
if target:
    s, _, _ = call('PATCH', f"{API}/auth/users/{target['id']}/role", {'role': 'ROLE_ADMIN'}, token=manager)
    check('SEC demo manager cannot promote a registered user to ADMIN (403)', s == 403, s)
    s, _, _ = call('PATCH', f"{API}/auth/users/{target['id']}/role", {'role': 'ROLE_ADMIN'}, token=ramesh)
    check('SEC a USER cannot change roles (403)', s == 403, s)

# ================================================================================================ business workflows
s, hotels, _ = call('GET', API + '/hotels')
check('BIZ hotel listing (public)', s == 200 and isinstance(hotels, list) and len(hotels) >= 1, (s, str(hotels)[:80]))
city = hotels[0]['city'] if isinstance(hotels, list) and hotels else 'Chennai'
s, rooms, _ = call('GET', API + f'/rooms?city={city}')
check(f'BIZ room search by city ({city})', s == 200 and isinstance(rooms, list) and len(rooms) >= 2, (s, str(rooms)[:80]))
room_ids = [r['id'] for r in rooms] if isinstance(rooms, list) else []

base = date.today() + timedelta(days=200 + secrets.randbelow(100))
if room_ids:
    s, quote, _ = call('GET', API + f'/rooms/{room_ids[0]}/price?checkIn={base}&checkOut={base + timedelta(days=2)}')
    check('BIZ dynamic price quote with nightly breakdown',
          s == 200 and quote.get('nights') == 2 and len(quote.get('nightlyBreakdown', [])) == 2 and quote.get('finalTotal'),
          (s, str(quote)[:150]))

    # concurrent booking protection
    outcomes = []
    lock = threading.Lock()

    def attempt(tok):
        st = book(tok, room_ids[0], base)[0]
        with lock:
            outcomes.append(st)

    threads = [threading.Thread(target=attempt, args=(ramesh if i % 2 else guest2,)) for i in range(8)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    check('BIZ 8 simultaneous bookings of one room/dates (two guests) -> exactly one 201, rest 409',
          outcomes.count(201) == 1 and outcomes.count(409) == 7, outcomes)

    # payment success
    s, bk, _ = book(ramesh, room_ids[1], base)
    bid = bk.get('bookingId') if isinstance(bk, dict) else None
    check('BIZ Ramesh creates a booking (PENDING hold)', s == 201 and bid, (s, bk))
    s, out, _ = call('POST', f'{API}/bookings/{bid}/pay', {'paymentMethod': 'UPI'}, ramesh)
    check('BIZ payment success -> booking CONFIRMED',
          s == 200 and out.get('booking', {}).get('bookingStatus') == 'CONFIRMED', (s, str(out)[:200]))
    s, pays, _ = call('GET', f'{API}/payments/booking/{bid}', token=ramesh)
    check('BIZ exactly one SUCCESS payment stored for the booking',
          s == 200 and len(pays) == 1 and pays[0]['status'] == 'SUCCESS', (s, pays))
    s, _, _ = call('GET', f'{API}/bookings/{bid}', token=guest2)
    check('ROLE Demo Guest 2 cannot read Ramesh\'s booking (403)', s == 403, s)
    s, mine, _ = call('GET', f'{API}/bookings/my', token=guest2)
    check('ROLE Demo Guest 2 does not see Ramesh\'s booking in "my bookings"',
          s == 200 and all(b['bookingId'] != bid for b in mine), (s, len(mine) if isinstance(mine, list) else mine))
    s, allb, _ = call('GET', f'{API}/bookings', token=manager)
    check('ROLE GrandVista Manager sees the booking in the admin list',
          s == 200 and any(b['bookingId'] == bid for b in allb), s)
    s, _, _ = call('POST', f'{API}/bookings/{bid}/cancel', token=guest2)
    check('ROLE Demo Guest 2 cannot cancel Ramesh\'s booking (403)', s == 403, s)

    # cancellation + refund
    s, out, _ = call('POST', f'{API}/bookings/{bid}/cancel', token=ramesh)
    s2, pays, _ = call('GET', f'{API}/payments/booking/{bid}', token=ramesh)
    s3, av, _ = call('GET', API + f'/rooms/{room_ids[1]}/availability?checkIn={base}&checkOut={base + timedelta(days=2)}')
    check('BIZ cancellation -> CANCELLED, payment REFUNDED, room available again',
          s == 200 and out.get('bookingStatus') == 'CANCELLED' and pays[0]['status'] == 'REFUNDED' and av.get('available') is True,
          (s, str(out)[:120], pays, av))

    # payment failure
    s, bk2, _ = book(guest2, room_ids[1], base + timedelta(days=5))
    bid2 = bk2.get('bookingId') if isinstance(bk2, dict) else None
    s, out, _ = call('POST', f'{API}/bookings/{bid2}/pay', {'paymentMethod': 'CARD', 'simulateFailure': True}, guest2)
    status_after = call('GET', f'{API}/bookings/{bid2}', token=guest2)[1]
    check('BIZ simulated payment failure is reported and nothing is confirmed',
          s in (200, 402, 409) and status_after.get('bookingStatus') != 'CONFIRMED', (s, str(out)[:150], status_after.get('bookingStatus')))
    s, pays, _ = call('GET', f'{API}/payments/booking/{bid2}', token=guest2)
    check('BIZ the failed attempt is stored as FAILED', s == 200 and any(p['status'] == 'FAILED' for p in pays), (s, pays))

# admin management (on throw-away data)
s, hotel, _ = call('POST', API + '/hotels', {'name': f'Cloud Test Hotel {RUN}', 'city': 'Testpur', 'state': 'Test State',
                                            'country': 'India', 'address': '1 Synthetic Road', 'starRating': 3},
                   token=manager)
check('ADMIN GrandVista Manager creates a hotel (201)', s == 201 and isinstance(hotel, dict) and hotel.get('id'), (s, str(hotel)[:150]))
s, _, _ = call('POST', API + '/hotels', {'name': 'Nope', 'city': 'X', 'state': 'X', 'country': 'India', 'address': 'X',
                                       'starRating': 3}, token=ramesh)
check('ROLE Ramesh cannot create a hotel (403)', s == 403, s)
s, inv, _ = call('GET', API + '/rooms/inventory-summary', token=manager)
check('ADMIN inventory summary', s == 200, (s, str(inv)[:100]))

# persistence: data written above is read back through a different service call path
s, mine, _ = call('GET', f'{API}/bookings/my', token=ramesh)
check('DB bookings persist and are listed for Ramesh', s == 200 and isinstance(mine, list) and len(mine) >= 1, s)

summary = {'passed': sum(r['pass'] for r in results), 'failed': sum(not r['pass'] for r in results),
           'total': len(results), 'gateway': GATEWAY, 'run': RUN}
print(json.dumps(summary))
if os.environ.get('GV_RESULTS'):
    with open(os.environ['GV_RESULTS'], 'w') as fh:
        json.dump({'summary': summary, 'results': results}, fh, indent=1)
sys.exit(1 if summary['failed'] else 0)
