"""Search the repository for secret VALUES without ever printing them."""
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..'))
SKIP_DIRS = {'.git', 'node_modules', 'target', '.metadata', 'dist', '.vite'}
SKIP_FILES = {'grandvista-local.properties'}   # the git-ignored real secrets file itself

# 1. secret values to look for: from the real (git-ignored) local file and from the environment - never printed
secrets = {}
local = f'{ROOT}/grandvista-local.properties'
if os.path.exists(local):
    for line in open(local, encoding='utf-8'):
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        if key in ('DB_PASSWORD', 'JWT_SECRET', 'INTERNAL_API_KEY', 'ADMIN_PASSWORD') and len(value) >= 6:
            secrets[f'real {key}'] = value
for key in ('DB_PASSWORD', 'JWT_SECRET', 'INTERNAL_API_KEY', 'ADMIN_PASSWORD'):      # the disposable stack's random values
    if len(os.environ.get(key, '')) >= 6:
        secrets[f'environment {key}'] = os.environ[key]

tracked_or_new = subprocess.run(['git', '-C', ROOT, 'ls-files', '--cached', '--others', '--exclude-standard'],
                                capture_output=True, text=True).stdout.splitlines()
hits = []
patterns = {
    'a JWT': re.compile(r'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}'),
    'a private key': re.compile(r'-----BEGIN [A-Z ]*PRIVATE KEY-----'),
    'an AWS-style key': re.compile(r'AKIA[0-9A-Z]{16}'),
    'a literal password assignment': re.compile(r'(?i)(password|secret|api[-_]?key)\s*[:=]\s*["\']?(?!\$\{|<|\s|["\']|\{|replace|your|choose|change|test|example|none|null|true|false|\*)[A-Za-z0-9+/=_!#$%^&*-]{8,}'),
}
scanned = 0
for rel in tracked_or_new:
    path = os.path.join(ROOT, rel)
    if any(part in SKIP_DIRS for part in rel.split('/')) or os.path.basename(rel) in SKIP_FILES or not os.path.isfile(path):
        continue
    if os.path.getsize(path) > 2_000_000:
        continue
    try:
        text = open(path, encoding='utf-8').read()
    except (UnicodeDecodeError, OSError):
        continue
    scanned += 1
    for label, value in secrets.items():
        if value in text:
            hits.append((rel, f'contains the {label}'))
    for label, pattern in patterns.items():
        for number, text_line in enumerate(text.split(chr(10)), 1):   # per line: blank "KEY=" must not swallow the next line
            if pattern.search(text_line):
                hits.append((f'{rel}:{number}', f'looks like {label}: {text_line.strip()[:70]}'))

print(f'scanned {scanned} files (tracked + untracked, git-ignored files excluded)')
for rel, why in hits:
    print('HIT', rel, why)
print('RESULT', 'CLEAN' if not hits else f'{len(hits)} potential hit(s)')
sys.exit(1 if hits else 0)
