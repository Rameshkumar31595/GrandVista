"""Bounded local load test against the ISOLATED stack (gateway -> 2 room-service instances, booking, payment, auth).

Measures latency/throughput/error rate and then proves booking consistency against the isolated PostgreSQL.
Single developer machine, single instance of every service except room-service: the numbers describe THIS setup only.
"""
import glob
import json
import os
import random
import secrets
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from datetime import date, timedelta

GATEWAY = 'http://localhost:8080/api'
HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.environ['GV_WORK_DIR'] + '/results'
PASSWORD = 'Aa1-' + secrets.token_hex(8)           # throw-away test-user password, new on every run
PG_JAR = sorted(glob.glob(os.path.expanduser('~/.m2/repository/org/postgresql/postgresql/*/postgresql-*.jar')))[-1]
os.makedirs(RESULTS, exist_ok=True)
USERS = 40
READ_SECONDS = 15
BOOKING_SECONDS = 25
lock = threading.Lock()
latencies = defaultdict(list)
statuses = defaultdict(Counter)


def call(method, path, body=None, token=None, op=None, want_headers=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GATEWAY + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode()
            status, headers = resp.status, dict(resp.headers)
            payload = json.loads(raw) if raw else None
    except urllib.error.HTTPError as err:
        raw = err.read().decode()
        status, headers = err.code, dict(err.headers)
        try:
            payload = json.loads(raw)
        except ValueError:
            payload = raw
    except Exception as err:  # timeout / connection reset
        status, headers, payload = 0, {}, str(err)
    elapsed = (time.perf_counter() - start) * 1000
    if op:
        with lock:
            latencies[op].append(elapsed)
            statuses[op][status] += 1
    return (status, payload, headers) if want_headers else (status, payload)


def pct(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[min(len(ordered) - 1, int(len(ordered) * p / 100))], 1)


def summarise(op):
    v = latencies[op]
    return {'requests': len(v), 'p50_ms': pct(v, 50), 'p95_ms': pct(v, 95), 'p99_ms': pct(v, 99), 'max_ms': round(max(v), 1) if v else None,
            'statuses': dict(statuses[op])}


def q(db, sql):
    out = subprocess.run(['java', '-cp', PG_JAR, f'{HERE}/SqlQuery.java', os.environ['DB_PORT'], db, os.environ['DB_USERNAME'],
                          os.environ['DB_PASSWORD'], sql, 'ro'], capture_output=True, text=True, cwd=HERE).stdout
    return [line for line in out.splitlines() if line and 'WARNING' not in line]


def run_threads(n, target):
    threads = [threading.Thread(target=target, args=(i,)) for i in range(n)]
    [t.start() for t in threads]
    [t.join() for t in threads]


report = {'config': {'concurrent_users': USERS, 'read_seconds': READ_SECONDS, 'booking_seconds': BOOKING_SECONDS}}

# ---- users --------------------------------------------------------------------------------------
tokens = []
stamp = int(time.time())
for i in range(USERS):
    email = f'load-{stamp}-{i}@example.com'
    call('POST', '/auth/register', {'name': f'Load User {i}', 'email': email, 'password': PASSWORD})
    s, b = call('POST', '/auth/login', {'email': email, 'password': PASSWORD}, op='login')
    tokens.append(b['accessToken'])
rooms = []
for city in ('Hyderabad', 'Vijayawada', 'Bengaluru', 'Chennai'):
    rooms += call('GET', f'/rooms?city={city}')[1]
room_ids = [r['id'] for r in rooms]
print(f'{USERS} users ready, {len(room_ids)} rooms', flush=True)

# ---- 1. load balancing across the two room-service instances ----------------------------------------
instances = Counter()
for _ in range(60):
    s, _, h = call('GET', '/rooms', want_headers=True)
    instances[{k.lower(): v for k, v in h.items()}.get('x-instance-id', 'none')] += 1
report['load_balancing'] = {'room_service_instances_seen': dict(instances)}
print('load balancing:', dict(instances), flush=True)

# ---- 2. read load -----------------------------------------------------------------------------------
deadline = time.time() + READ_SECONDS
base_in = date.today() + timedelta(days=60)


def reader(i):
    rng = random.Random(i)
    while time.time() < deadline:
        pick = rng.random()
        rid = rng.choice(room_ids)
        d_in = base_in + timedelta(days=rng.randint(0, 20))
        d_out = d_in + timedelta(days=rng.randint(1, 4))
        if pick < 0.5:
            call('GET', f'/rooms?city={rng.choice(["Hyderabad", "Chennai", "Bengaluru", "Vijayawada"])}&checkIn={d_in}&checkOut={d_out}', op='search')
        elif pick < 0.8:
            call('GET', f'/rooms/{rid}/availability?checkIn={d_in}&checkOut={d_out}', op='availability')
        else:
            call('GET', f'/rooms/{rid}/price?checkIn={d_in}&checkOut={d_out}', op='price_quote')


t0 = time.time()
run_threads(USERS, reader)
read_elapsed = time.time() - t0
read_total = sum(len(latencies[o]) for o in ('search', 'availability', 'price_quote'))
report['read_load'] = {'duration_s': round(read_elapsed, 1), 'total_requests': read_total,
                       'throughput_rps': round(read_total / read_elapsed, 1),
                       **{op: summarise(op) for op in ('search', 'availability', 'price_quote')}}
print('read load done:', report['read_load']['throughput_rps'], 'req/s', flush=True)

# ---- 3. hot-spot: everybody wants the same room on the same dates ----------------------------------------
hot_room = room_ids[0]
hot_in = date.today() + timedelta(days=1500)
hot_results = []


def hot(i):
    s, b = call('POST', '/bookings', {'roomId': hot_room, 'checkIn': hot_in.isoformat(), 'checkOut': (hot_in + timedelta(days=2)).isoformat(), 'numberOfGuests': 2},
                tokens[i], op='hot_create_booking')
    hot_results.append(s)


run_threads(USERS, hot)
report['hot_spot'] = {'simultaneous_requests': USERS, 'status_counts': dict(Counter(hot_results)),
                      'exactly_one_winner': hot_results.count(201) == 1}
print('hot spot:', report['hot_spot'], flush=True)

# ---- 4. promotional traffic: bookings + payments + cancellations in parallel ------------------------------
base = date.today() + timedelta(days=1000)
deadline = time.time() + BOOKING_SECONDS
completed = Counter()


def shopper(i):
    rng = random.Random(1000 + i)
    k = 0
    while time.time() < deadline and k < 12:
        rid = room_ids[(i + k) % len(room_ids)]
        d_in = base + timedelta(days=i * 30 + k * 3)          # unique window per (worker, iteration)
        s, b = call('POST', '/bookings', {'roomId': rid, 'checkIn': d_in.isoformat(), 'checkOut': (d_in + timedelta(days=2)).isoformat(), 'numberOfGuests': 2},
                    tokens[i], op='create_booking')
        k += 1
        if s != 201:
            continue
        decline = rng.random() < 0.15
        s, out = call('POST', f"/bookings/{b['bookingId']}/pay", {'paymentMethod': 'UPI', 'simulateFailure': decline}, tokens[i], op='pay')
        completed['declined' if decline else 'paid'] += 1 if s == 200 else 0
        if s == 200 and not decline and rng.random() < 0.3:
            call('POST', f"/bookings/{b['bookingId']}/cancel", None, tokens[i], op='cancel')
            completed['cancelled'] += 1
        call('GET', '/bookings/my', None, tokens[i], op='my_bookings')


t0 = time.time()
run_threads(USERS, shopper)
elapsed = time.time() - t0
workflow_ops = ('create_booking', 'pay', 'cancel', 'my_bookings')
total = sum(len(latencies[o]) for o in workflow_ops)
report['promotional_traffic'] = {'duration_s': round(elapsed, 1), 'total_requests': total, 'throughput_rps': round(total / elapsed, 1),
                                 'workflow_outcomes': dict(completed), **{op: summarise(op) for op in workflow_ops}}
print('promotional traffic done:', report['promotional_traffic']['throughput_rps'], 'req/s', flush=True)

# ---- error rate ---------------------------------------------------------------------------------------
unexpected = {}
for op, counter in statuses.items():
    bad = {s: n for s, n in counter.items() if s == 0 or s >= 500}
    if bad:
        unexpected[op] = bad
all_requests = sum(sum(c.values()) for c in statuses.values())
report['error_rate'] = {'total_requests': all_requests, 'unexpected_5xx_or_timeouts': unexpected,
                        'unexpected_count': sum(sum(b.values()) for b in unexpected.values())}

# ---- consistency proof against the isolated databases ---------------------------------------------------
time.sleep(5)  # let in-flight recovery / expiry settle
checks = {}
checks['overlapping_blocking_holds'] = q('grandvista_room_db', """
    select count(*) from room_holds a join room_holds b on a.room_id = b.room_id and a.id < b.id
     where a.check_in < b.check_out and a.check_out > b.check_in
       and (a.status = 'CONFIRMED' or (a.status = 'HELD' and a.expires_at > now()))
       and (b.status = 'CONFIRMED' or (b.status = 'HELD' and b.expires_at > now()))""")
checks['bookings_with_more_than_one_successful_charge'] = q('grandvista_payment_db', """
    select count(*) from (select booking_id from payments where status in ('SUCCESS','REFUNDED') group by booking_id having count(*) > 1) x""")
checks['bookings_by_status'] = q('grandvista_booking_db', "select status || '=' || count(*) from bookings group by status order by 1")
checks['confirmed_bookings_without_payment'] = q('grandvista_booking_db', "select count(*) from bookings where status = 'CONFIRMED' and payment_id is null")
checks['bookings_stuck_in_intermediate_state'] = q('grandvista_booking_db', "select count(*) from bookings where status in ('PAYMENT_IN_PROGRESS','CANCELLATION_PENDING')")
confirmed_ids = q('grandvista_booking_db', "select id from bookings where status = 'CONFIRMED'")
success_ids = q('grandvista_payment_db', "select booking_id from payments where status = 'SUCCESS'")
cancelled_with_live_payment = q('grandvista_booking_db', "select id from bookings where status = 'CANCELLED' and payment_id is not null")
refunded_ids = set(q('grandvista_payment_db', "select booking_id from payments where status = 'REFUNDED'"))
failed_ids = set(q('grandvista_payment_db', "select booking_id from payments where status = 'FAILED'"))
checks['confirmed_bookings_without_a_SUCCESS_payment'] = len(set(confirmed_ids) - set(success_ids))
checks['SUCCESS_payments_whose_booking_is_not_CONFIRMED'] = len(set(success_ids) - set(confirmed_ids))
checks['cancelled_bookings_holding_money_(paid, neither refunded nor failed)'] = len(
    [i for i in cancelled_with_live_payment if i not in refunded_ids and i not in failed_ids])
checks['confirmed_holds_vs_confirmed_bookings'] = {
    'confirmed_holds': q('grandvista_room_db', "select count(*) from room_holds where status = 'CONFIRMED'"),
    'confirmed_bookings': q('grandvista_booking_db', "select count(*) from bookings where status = 'CONFIRMED'")}
report['consistency'] = checks
print(json.dumps(report['consistency'], indent=1), flush=True)

json.dump(report, open(f'{RESULTS}/load_test_results.json', 'w'), indent=1)
print('REPORT', json.dumps({k: report[k] for k in ('load_balancing', 'hot_spot', 'error_rate')}))
