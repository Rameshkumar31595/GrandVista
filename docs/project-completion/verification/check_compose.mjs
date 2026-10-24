import { parse } from 'yaml';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../../', import.meta.url));   // repository root
const compose = parse(readFileSync(root + 'docker-compose.yml', 'utf8'), { merge: true });
const envExample = readFileSync(root + '.env.example', 'utf8');
const envKeys = new Set(envExample.split('\n').filter((l) => /^[A-Z_]+=/.test(l)).map((l) => l.split('=')[0]));
const out = [];
const check = (name, ok, detail = '') => { out.push({ name, ok: !!ok }); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok ? '' : ' -> ' + detail}`); };

const services = compose.services;
const names = Object.keys(services);
check('compose defines the 8 expected services', ['postgres', 'eureka-server', 'auth-service', 'room-service', 'payment-service', 'booking-service', 'api-gateway', 'frontend'].every((n) => names.includes(n)), names.join(','));

// depends_on targets exist and form no cycle
const deps = Object.fromEntries(names.map((n) => [n, Object.keys(services[n].depends_on || {})]));
check('every depends_on target is a defined service', names.every((n) => deps[n].every((d) => names.includes(d))));
const visiting = new Set(); const done = new Set(); let cycle = false;
const visit = (n) => { if (done.has(n)) return; if (visiting.has(n)) { cycle = true; return; } visiting.add(n); deps[n].forEach(visit); visiting.delete(n); done.add(n); };
names.forEach(visit);
check('startup dependencies have no cycle', !cycle);
check('every depends_on waits for service_healthy and the target has a healthcheck',
  names.every((n) => Object.entries(services[n].depends_on || {}).every(([d, c]) => c.condition === 'service_healthy' && services[d].healthcheck)));

// published ports
const published = names.flatMap((n) => (services[n].ports || []).map((p) => ({ n, host: String(p).split(':')[0] })));
check('published host ports are unique', new Set(published.map((p) => p.host)).size === published.length);
check('only gateway, UI and Eureka are published (business services + database stay internal)',
  published.map((p) => p.n).sort().join() === ['api-gateway', 'eureka-server', 'frontend'].sort().join(), published.map((p) => p.n).join());

// healthcheck port matches the port the service really listens on
const ports = { 'eureka-server': 9000, 'auth-service': 8081, 'room-service': 8082, 'payment-service': 8084, 'booking-service': 8083, 'api-gateway': 8080 };
for (const [n, port] of Object.entries(ports)) {
  const test = services[n].healthcheck?.test?.join(' ') || '';
  check(`${n}: healthcheck probes port ${port}`, test.includes(`localhost:${port}/actuator/health`), test);
}
// the service ports in compose match each service's application.properties default
for (const [n, port] of Object.entries(ports)) {
  const props = readFileSync(`${root}${n}/src/main/resources/application.properties`, 'utf8');
  check(`${n}: application.properties default port is ${port}`, new RegExp('server[.]port=[$][{][A-Z_]+:' + port + '[}]').test(props));
}
// environment variables referenced by compose exist in .env.example
const text = readFileSync(root + 'docker-compose.yml', 'utf8');
const referenced = [...new Set([...text.matchAll(/\$\{([A-Z_]+)[:?-]/g)].map((m) => m[1]))];
check('every ${VARIABLE} used by compose is documented in .env.example', referenced.every((v) => envKeys.has(v)), referenced.filter((v) => !envKeys.has(v)).join(','));
const required = [...text.matchAll(/\$\{([A-Z_]+):\?/g)].map((m) => m[1]);
check('required secrets are blank in .env.example (no credential committed)', ['DB_PASSWORD', 'JWT_SECRET', 'INTERNAL_API_KEY', 'ADMIN_PASSWORD'].every((k) => new RegExp(`^${k}=$`, 'm').test(envExample)));
check('compose refuses to start when a required secret is missing', ['DB_PASSWORD', 'JWT_SECRET', 'INTERNAL_API_KEY', 'ADMIN_PASSWORD'].every((k) => required.includes(k)));

// application properties actually read the env vars compose sets
const reads = { 'auth-service': ['DB_HOST', 'DB_PORT', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'EUREKA_URL', 'ADMIN_PASSWORD'],
  'room-service': ['DB_HOST', 'DB_PORT', 'JWT_SECRET', 'INTERNAL_API_KEY', 'EUREKA_URL', 'HOLD_MINUTES', 'SEED_DEMO_DATA'],
  'payment-service': ['DB_HOST', 'JWT_SECRET', 'INTERNAL_API_KEY', 'EUREKA_URL', 'PAYMENT_MAX_AMOUNT'],
  'booking-service': ['DB_HOST', 'JWT_SECRET', 'INTERNAL_API_KEY', 'EUREKA_URL'],
  'api-gateway': ['JWT_SECRET', 'EUREKA_URL', 'CORS_ALLOWED_ORIGINS'] };
for (const [n, vars] of Object.entries(reads)) {
  const props = readFileSync(`${root}${n}/src/main/resources/application.properties`, 'utf8');
  check(`${n}: application.properties consumes ${vars.join(', ')}`, vars.every((v) => props.includes('${' + v)), vars.filter((v) => !props.includes('${' + v)).join(','));
}
// files referenced by compose exist
for (const f of ['docker/service.Dockerfile', 'docker/frontend.Dockerfile', 'docker/nginx.conf', 'docker/postgres/init-databases.sh', '.dockerignore', '.gitattributes']) {
  check(`file exists: ${f}`, existsSync(root + f));
}
// Dockerfile hygiene
const svc = readFileSync(root + 'docker/service.Dockerfile', 'utf8');
check('service image runs as a non-root user', /^USER grandvista/m.test(svc));
check('service image copies no secrets (only pom.xml and src)', !/grandvista-local|\.env/.test(svc.replace(/#.*$/gm, '')));
const ignore = readFileSync(root + '.dockerignore', 'utf8');
check('.dockerignore keeps grandvista-local.properties and .env out of every build context', /grandvista-local\.properties/.test(ignore) && /^\.env$/m.test(ignore));
const init = readFileSync(root + 'docker/postgres/init-databases.sh', 'utf8');
check('init script creates all four service databases', ['auth', 'room', 'booking', 'payment'].every((d) => init.includes(`grandvista_${d}_db`)));
check('init script uses LF line endings', !init.includes('\r'));
const failed = out.filter((o) => !o.ok).length;
console.log(JSON.stringify({ passed: out.length - failed, failed, total: out.length }));
process.exit(failed ? 1 : 0);
