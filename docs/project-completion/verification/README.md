# Verification kit

Everything used to produce the live evidence in `TEST_VERIFICATION_REPORT.md`, so a reviewer can repeat it.
**None of it touches your real PostgreSQL (port 5433) or your real `grandvista-local.properties`:** the stack below runs on its own
PostgreSQL server (port 5543) with a data directory under `$GV_WORK_DIR` and **randomly generated secrets** that are written to
`$GV_WORK_DIR/env.sh` (outside the repository) and never printed.

| File | Purpose |
|---|---|
| `start-isolated-stack.sh` / `stop-isolated-stack.sh` | disposable PostgreSQL + Eureka, auth, room (×2), payment, booking, gateway |
| `svc.sh` | start/stop one service (failure injection) |
| `live_saga.py` | concurrent bookings, duplicate pay clicks, idempotent payments, **payment/refund outage + recovery** (31 checks) |
| `security_checks.py` | forged/expired/tampered JWTs, role escalation, cross-guest access, validation, error leakage, CORS, secrets in logs (56 checks) |
| `load_test.py` | load balancing, bounded read and booking load, hot-spot, **database consistency proof** |
| `ui_e2e.mjs` | real Chrome journeys: guest, payment outage, session, admin, mobile layout (34 checks) |
| `check_compose.mjs` | static validation of `docker-compose.yml` and the Docker files (37 checks) – Docker itself is **not** needed |
| `secret_scan.py` | searches the repository for secret *values* (without printing them) |
| `SqlQuery.java` | read-only JDBC helper (no `psql` needed) |

## Run (Git Bash on Windows; JDK 25, Maven with a populated offline repository, PostgreSQL binaries, Node 20+, Chrome)

```bash
cd <repo>
bash docs/project-completion/verification/start-isolated-stack.sh     # first run builds missing jars, creates secrets and databases
source "${GV_WORK_DIR:-${TMPDIR:-/tmp}/grandvista-verification}/env.sh"

# Postman collection (state-changing: disposable stack only!)
NODE_OPTIONS=--use-system-ca npx newman run postman/GrandVista.postman_collection.json \
  -e postman/GrandVista-Local.postman_environment.json --env-var "adminPassword=$ADMIN_PASSWORD"

python docs/project-completion/verification/live_saga.py
python docs/project-completion/verification/security_checks.py
python docs/project-completion/verification/load_test.py

# real-browser journeys: needs the Vite dev server and puppeteer-core
(cd grandvista-frontend && npm install && npm run dev) &              # http://localhost:5173
(cd docs/project-completion/verification && npm install && node ui_e2e.mjs)

# backend unit/integration tests incl. the real-PostgreSQL concurrency test (env vars come from env.sh)
for m in eureka-server api-gateway auth-service room-service booking-service payment-service; do (cd $m && mvn -o test); done
(cd grandvista-frontend && npm test)

# static checks
(cd docs/project-completion/verification && node check_compose.mjs)
python docs/project-completion/verification/secret_scan.py

bash docs/project-completion/verification/stop-isolated-stack.sh
```

Results are written to `$GV_WORK_DIR/results/*.json`. The `ui_e2e.mjs` and `live_saga.py` outage scenarios stop and restart the
Payment Service of the disposable stack; recovery timers there are shortened to 15 s / 10 s through environment variables
(`GRANDVISTA_BOOKING_PAYMENTRECOVERYAFTER`, `GRANDVISTA_BOOKING_CANCELLATIONRETRYAFTER`); the application defaults are 60 s / 30 s.

`.dockerignore`, `.env` and `grandvista-local.properties` are unaffected by anything here.
