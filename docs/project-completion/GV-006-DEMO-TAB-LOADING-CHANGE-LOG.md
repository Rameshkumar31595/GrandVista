# GV-006 - Demo Login tab hidden while the demo-accounts request is pending

Status: published in the commit that adds this file (base: main c298b48). Independent of GV-005 (Rooms). Deployment and live checks are recorded in a follow-up docs-only entry.

## Observed (live, read-only, 2026-10-29 17:19-17:23 IST)
- The deployed frontend is the GV-003 build (bundle index-BXDqu4Yj.js contains the demo/accounts call and the "Demo accounts cannot be reached" panel).
- With the services asleep (auth shut down for idle at 17:00:35 IST), GET /api/auth/demo/accounts stayed pending for 55.25s (the GV-002 retry budget), then returned 503. While it was pending the login page showed no Demo Login tab; after the 503 both tabs appeared.
- Cause: the tab was shown only after a result (accounts, 401/429 gate or a non-403 error). While the request is pending none applied, so the tab was hidden. The longer pending time from the GV-002 retry made this window visible to users. Not a backend or setting change.

## Change (LoginPage.jsx only)
- The Demo Login tab is shown from the first render while the first request is pending. Opening it shows "Checking the demo accounts - the server may be waking up. This can take a while, please wait." The normal login form stays usable.
- When the answer arrives: accounts -> cards; 401/429 -> reviewer-code gate (unchanged); 403 -> the tab is removed (demo disabled, unchanged); other error -> the existing retry panel (unchanged). Try again shows the waiting message while the new request runs.
- A closed page ignores a late answer.
- No gateway, backend, setting or database change.

## Tests (local) - frontend 124 pass, 0 fail
New: tab visible and form usable while pending; pending -> cards; pending -> 401 gate; pending -> 429 gate; pending -> 503 retry panel; pending -> 403 tab removed; Try again shows the waiting message; Try again pending -> 401 gate; late answer after unmount. Two existing tests adjusted for the pending phase.

## Limits
- The cold start still happens and its length is not bounded by this change; the page now says so instead of hiding the tab.

## Revert
`git apply -R GV-006-demo-tab-loading.patch` or revert the commit. A live revert needs a redeploy of the web service.
