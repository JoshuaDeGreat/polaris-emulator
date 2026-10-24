# Heaven staff area

The homepage footer links to https://heavenhotel.uk/staff. Its sign-in page and news editor share the homepage's branding. Staff use their existing hotel account; Polaris requires current Housekeeping and hotel-area permissions and applies its lockdown rules. Website news access no longer requires an online game session. A visible footer link grants no permission.

The homepage Worker serves the editor and forwards only `/api/auth/login` and `/api/community/staff/news` to the fixed production server. Before forwarding any POST, it checks the exact browser Origin, JSON content type and bounded body size. It then sets the upstream Origin to the fixed server origin so Polaris's original same-origin guard continues to apply there. Browser cookies, upstream cookies and CORS headers are not forwarded. Private responses are not cached. Invalid tokens and insufficient permissions remain server-side failures. The member site uses a revocable HttpOnly website session cookie. See MEMBER_AREA.md for session details.

Presentation source: `Emulator/src/main/resources/community-staff.html`.
Routing source: `deploy/staff-routes.mjs`.
Homepage source: `deploy/homepage-shell.mjs`.
Generate the deployable Worker with `node deploy/build-homepage-worker.mjs` and run `node --test deploy/staff-routes.test.mjs`.

Future admin sections should reuse this entry point and existing account identity, with a separate server permission check per action. News-editor access must never imply user-management, currency, permissions or server-control access. Existing in-hotel Housekeeping controls retain their own gates.
