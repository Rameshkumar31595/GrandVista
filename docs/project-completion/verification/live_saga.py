"""Live distributed-consistency scenarios against the ISOLATED stack (PostgreSQL on :5543, never the real DB).

Needs the stack from start-isolated-stack.sh (source its env.sh first). Payment-service is stopped/started with svc.sh to inject outages.
Prints one PASS/FAIL line per check and a JSON summary at the end.
"""
import json
import os
import secrets
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

GATEWAY = 'http://localhost:8080/api'
PAYMENT_DIRECT = 'http://localhost:8084'
INTERNAL_KEY = os.environ['INTERNAL_API_KEY']
SVC = os.environ['GV_SVC_SCRIPT']              # start/stop one service (failure injection)
RESULTS = os.environ['GV_WORK_DIR'] + '/results'
PASSWORD = 'Aa1-' + secrets.token_hex(8)         # throw-away test-user password, new on every run
os.makedirs(RESULTS, exist_ok=True)
results = []


def call(method, url, body=None, token=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=40) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as err:
        raw = err.read().decode()
        try:
            return err.code, json.loads(raw)
        except ValueError:
            return err.code, raw


def check(name, condition, detail=''):
    results.append({'check': name, 'pass': bool(condition), 'detail': str(detail)[:200]})
    print(('PASS ' if condition else 'FAIL ') + name + ('' if condition else '  -> ' + str(detail)[:200]), flush=True)


def register_and_login(tag):
    email = f'saga-{tag}-{int(time.time() * 1000)}@example.com'
    call('POST', GATEWAY + '/auth/register', {'name': 'Saga Tester', 'email': email, 'password': PASSWORD})
    status, body = call('POST', GATEWAY + '/auth/login', {'email': email, 'password': PASSWORD})
    assert status == 200, body
    return body['accessToken'] if 'accessToken' in body else body['token']


def book(token, room_id, check_in, nights=2):
    return call('POST', GATEWAY + '/bookings',
                {'roomId': room_id, 'checkIn': check_in.isoformat(),
                 'checkOut': (check_in + timedelta(days=nights)).isoformat(), 'numberOfGuests': 2}, token)


def pay(token, booking_id, fail=False):
    return call('POST', f'{GATEWAY}/bookings/{booking_id}/pay', {'paymentMethod': 'UPI', 'simulateFailure': fail}, token)


def booking(token, booking_id):
    return call('GET', f'{GATEWAY}/bookings/{booking_id}', token=token)[1]


def payments_of(token, booking_id):
    return call('GET', f'{GATEWAY}/payments/booking/{booking_id}', token=token)[1]


def available(room_id, check_in, nights=2):
    status, body = call('GET', f'{GATEWAY}/rooms/{room_id}/availability?checkIn={check_in}&checkOut={check_in + timedelta(days=nights)}')
    return body['available']


def wait_health(port, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            if urllib.request.urlopen(f'http://localhost:{port}/actuator/health', timeout=3).status == 200:
                return True
        except Exception:
            time.sleep(2)
    return False


def poll(fn, timeout=240, interval=5):
    end = time.time() + timeout
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(interval)
    return None


def sh(*cmd):
    subprocess.run(['bash', *cmd], check=False)


# --------------------------------------------------------------------------------------------------
today = date.today()
base = today + timedelta(days=300)          # far away from the Postman collection's dates
rooms = [r['id'] for r in call('GET', GATEWAY + '/rooms?city=Chennai')[1]] + \
        [r['id'] for r in call('GET', GATEWAY + '/rooms?city=Bengaluru')[1]]
token = register_and_login('main')
other = register_and_login('other')

# ---- S1: concurrent bookings of the same room / overlapping dates -------------------------------
room = rooms[0]
outcomes = []


def attempt(i):
    outcomes.append(book(token, room, base)[0])


threads = [threading.Thread(target=attempt, args=(i,)) for i in range(12)]
[t.start() for t in threads]
[t.join() for t in threads]
check('S1 12 simultaneous bookings of the same room+dates -> exactly one 201', outcomes.count(201) == 1, outcomes)
check('S1 all other attempts are 409 conflicts', outcomes.count(409) == 11, outcomes)
s, b2 = book(token, room, base + timedelta(days=2))
check('S1 back-to-back stay (check-in == previous check-out) is accepted', s == 201, (s, b2))
s, _ = book(other, room, base + timedelta(days=1))
check('S1 partially overlapping stay by another guest is refused', s == 409, s)

# ---- S2: duplicate pay clicks + idempotent payment ---------------------------------------------
s, b = book(token, rooms[1], base)
bid = b['bookingId']
pay_status = []


def pay_attempt():
    pay_status.append(pay(token, bid)[0])


threads = [threading.Thread(target=pay_attempt) for _ in range(2)]
[t.start() for t in threads]
[t.join() for t in threads]
check('S2 two parallel "Pay" clicks -> one 200 and one 409', sorted(pay_status) == [200, 409], pay_status)
check('S2 booking is CONFIRMED', booking(token, bid)['bookingStatus'] == 'CONFIRMED')
check('S2 exactly one payment row exists for the booking', len(payments_of(token, bid)) == 1, payments_of(token, bid))
amount = booking(token, bid)['totalAmount']
direct = [call('POST', PAYMENT_DIRECT + '/internal/payments',
               {'bookingId': bid, 'amount': amount, 'method': 'UPI'}, token, {'X-Internal-Api-Key': INTERNAL_KEY})
          for _ in range(3)]
check('S2 replayed internal payment requests return the SAME successful payment',
      len({d[1]['paymentId'] for d in direct}) == 1 and all(d[1]['status'] == 'SUCCESS' for d in direct), direct)
check('S2 still exactly one payment row after the replays', len(payments_of(token, bid)) == 1)
s, _ = call('POST', PAYMENT_DIRECT + '/internal/payments', {'bookingId': bid, 'amount': 1.0, 'method': 'UPI'}, token,
            {'X-Internal-Api-Key': INTERNAL_KEY})
check('S2 replay with a different amount is rejected (409)', s == 409, s)

# ---- S3: payment-service outage in the middle of the workflow ---------------------------------
s, ba = book(token, rooms[2], base)       # A: pay during outage, no payment ever recorded
s, bb = book(token, rooms[3], base)       # B: confirmed first, cancelled during outage
s, bc = book(token, rooms[4], base)       # C: pay during outage, charge "happens" but the response is lost
A, B, C = ba['bookingId'], bb['bookingId'], bc['bookingId']
pay(token, B)
check('S3 setup: booking B confirmed', booking(token, B)['bookingStatus'] == 'CONFIRMED')

sh(SVC, 'stop', '8084')
time.sleep(3)

s, out = pay(token, A)
check('S3 pay during outage -> 202 with UNKNOWN (not a failure, not a cancel)',
      s == 202 and out['paymentStatus'] == 'UNKNOWN', (s, out))
check('S3 booking A is PAYMENT_IN_PROGRESS (not cancelled)', booking(token, A)['bookingStatus'] == 'PAYMENT_IN_PROGRESS')
s, _ = call('POST', f'{GATEWAY}/bookings/{A}/cancel', token=token)
check('S3 cancelling a booking whose payment is in progress is refused (409)', s == 409, s)
s, _ = pay(token, A)
check('S3 a second pay click while in progress is refused (409)', s == 409, s)
s, out = pay(token, C)
check('S3 booking C also parked as 202 / UNKNOWN', s == 202, (s, out))

s, out = call('POST', f'{GATEWAY}/bookings/{B}/cancel', token=token)
check('S3 cancel of confirmed B during refund outage -> CANCELLATION_PENDING', booking(token, B)['bookingStatus'] == 'CANCELLATION_PENDING', (s, out))
check('S3 room of B is STILL reserved while the refund is outstanding',
      available(rooms[3], base) is False)

sh(SVC, 'start', 'payment', 'payment-service')
check('S3 payment-service back up', wait_health(8084))
time.sleep(6)
amount_c = booking(token, C)['totalAmount']
sim = call('POST', PAYMENT_DIRECT + '/internal/payments', {'bookingId': C, 'amount': amount_c, 'method': 'UPI'}, token,
           {'X-Internal-Api-Key': INTERNAL_KEY})
check('S3 simulated "charge happened but response was lost" for C', sim[0] in (200, 201) and sim[1]['status'] == 'SUCCESS', sim)

print('waiting for the recovery job ...', flush=True)
resolved = poll(lambda: all(booking(token, x)['bookingStatus'] in ('PENDING', 'CONFIRMED', 'CANCELLED') for x in (A, B, C)))
check('S3 recovery job resolved A, B and C', resolved)
check('S3 A (no payment recorded, hold still valid) is back to PENDING', booking(token, A)['bookingStatus'] == 'PENDING', booking(token, A))
bB = booking(token, B)
check('S3 B is CANCELLED with the payment refunded', bB['bookingStatus'] == 'CANCELLED' and 'refunded' in bB['statusReason'], bB)
check('S3 B refund really happened (payment REFUNDED)', payments_of(token, B)[0]['status'] == 'REFUNDED', payments_of(token, B))
check('S3 room of B is available again only AFTER the refund', available(rooms[3], base) is True)
bC = booking(token, C)
check('S3 C is CONFIRMED from the recovered payment', bC['bookingStatus'] == 'CONFIRMED' and bC['paymentId'] is not None, bC)
check('S3 C has exactly one payment', len(payments_of(token, C)) == 1)

s, out = pay(token, A)
check('S3 guest can pay A after recovery -> CONFIRMED', s == 200 and out['booking']['bookingStatus'] == 'CONFIRMED', (s, out))

# ---- S4: cancel of confirmed booking, healthy path ------------------------------------------------
s, bd = book(token, rooms[5], base)
D = bd['bookingId']
pay(token, D)
s, out = call('POST', f'{GATEWAY}/bookings/{D}/cancel', token=token)
check('S4 healthy cancel -> CANCELLED, refunded, room free',
      out['bookingStatus'] == 'CANCELLED' and payments_of(token, D)[0]['status'] == 'REFUNDED' and available(rooms[5], base), out)
s, _ = call('POST', f'{GATEWAY}/bookings/{D}/cancel', token=token)
check('S4 cancelling again is a clean 409', s == 409, s)
s, _ = call('POST', f'{GATEWAY}/bookings/{A}/cancel', token=other)
check('S4 another guest cannot cancel someone else\'s booking (403)', s == 403, s)

summary = {'passed': sum(r['pass'] for r in results), 'failed': sum(not r['pass'] for r in results), 'total': len(results)}
print(json.dumps(summary))
json.dump({'summary': summary, 'results': results}, open(RESULTS + '/live_saga_results.json', 'w'), indent=1)
sys.exit(1 if summary['failed'] else 0)
