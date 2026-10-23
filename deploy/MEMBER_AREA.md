# Heaven member area

The homepage's Log in button opens `/account`. Players use their existing hotel credentials. The dashboard shows only the signed-in player's profile, email verification, current/saved wallet balances and up to 50 owned rooms. Staff area appears only when the server grants Housekeeping access; news editing has its own hotel-area permission. `/staff` rechecks permissions and uses the same website session; the game does not need to be open.

Website login uses `/api/auth/website-login` with the existing rate, ban, maintenance and Turnstile checks, but does not mint or replace an SSO ticket. A 256-bit opaque session is stored only as a SHA-256 digest in `website_sessions`. It expires after 30 minutes, is bound to the current password hash and access-token version, and is deleted on website logout. The browser receives only a Secure, HttpOnly, SameSite=Lax `__Host-` cookie. No session token is returned to page JavaScript, local storage or URLs. Resetting/changing the password invalidates existing website sessions. Website logout does not revoke hotel tokens.

News permissions use the current database rank, active individual overrides, account bans and the Housekeeping lockdown. Explicit denials override rank permissions. No other Housekeeping or administrative actions are exposed. Existing hotel endpoints and their token format remain supported.

All website POSTs require the exact browser Origin and JSON content type; requests have bounded bodies. Only fixed account/news endpoints are proxied to Polaris. Cookies and private upstream CORS/cache metadata are not forwarded. The edge-provided CF-Connecting-IP header is retained for the server’s existing trusted-proxy IP/ban/rate-limit handling. The dashboard account selector is always the authenticated subject, never a URL parameter. Private pages and APIs are no-store, non-frameable and noindex.

UI source: `Emulator/src/main/resources/community-account.html` and `community-staff.html`.
Worker entry points: `deploy/member-routes.mjs` and `staff-routes.mjs`.
Build: `node deploy/build-homepage-worker.mjs`.
Tests: `node --test deploy/member-routes.test.mjs deploy/staff-routes.test.mjs` plus the Website* Java tests, CommunityEndpointsTest and staging session/login/logout verification.

Deploy the server and its backed-up migration before activating the matching homepage Worker. For UI rollback, redeploy the previous homepage Worker version; the new session table can remain safely in place.
