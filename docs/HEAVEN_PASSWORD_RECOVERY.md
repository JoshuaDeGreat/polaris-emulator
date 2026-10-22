# Heaven Hotel password recovery — staging checklist

This feature is intentionally developed on `feature/heaven-password-recovery` while Railway builds are paused. Do not merge until both repositories are deployed together and tested.

## Already present
- Octane login includes Forgot Password, which posts to `/api/auth/forgot-password`.
- Polaris stores registration emails in `users.mail` and bcrypt hashes in `users.password`.
- Polaris already creates rows in `password_resets`, with a runtime schema repair migration.

## New flow
1. Forgot Password requires a verified mailbox, applies an atomic 5-minute per-account cooldown and generates a 32-byte random token, stores only its SHA-256 digest and expires it in 20 minutes.
2. Resend sends a fragment-based link to the Octane client recovery screen. The token is not sent to the frontend web server.
3. Octane posts `token`, `newPassword`, and `confirmPassword` to `/api/auth/reset-password`.
4. Polaris locks the token row, verifies expiry, bcrypt-hashes the new password, revokes remember-token families, invalidates access tokens and SSO ticket, deletes the used token and commits atomically.

## Required deployment settings
Add these settings to Polaris configuration via a secure secrets mechanism (do not commit a real key):
- Hosting environment variable `SMTP_PROVIDER=resend` (or the equivalent non-secret emulator setting)
- Hosting environment secret `RESEND_API_KEY=<Resend sending API key>` **(never store this key in the emulator database)**
- `smtp.from_address=accounts@heavenhotel.uk`
- `smtp.from_name=Heaven Hotel`
- `password.reset.url=https://heavenhotel.uk/client/?recovery=1`
- `email.verify.url=https://heavenhotel.uk/client/?verify=1`

Verify the actual public client URL and override `password.reset.url` if different. The query must include `recovery=1`. Polaris appends `#token=...` to the configured URL; verification similarly uses `verify=1#token=...`. The client must resolve its `login.reset.endpoint` to the Polaris API, particularly when the API and client are hosted on different origins.

Resend must show the sending domain as verified before testing delivery. Do not use a key with unnecessary permissions. Rotate Railway environment credentials in response to Railway's incident before redeployment.

## Required tests before merge
- Build Polaris with Maven and Octane with Yarn; run both test suites.
- Check a registered email receives one reset link with the correct Heaven Hotel sender.
- Check an unregistered email receives the same generic UI response but no email.
- Confirm expired, malformed and reused tokens are rejected.
- Confirm concurrent submissions cannot use a token twice.
- Confirm old passwords, access tokens, SSO tickets and remember sessions are invalid after reset.
- Confirm new password logs in and rooms, inventory, badges and credits remain untouched.
- Confirm email rate limiting and Turnstile protection work.
- Test the link on desktop and mobile and check that the token disappears from browser URL.
- Confirm no secret or raw token appears in logs.
- Review `password_resets` schema compatibility on a backup database, not the live database.

## Email ownership and legacy accounts
- Registration sends a verification email. Unverified users may log in but cannot recover passwords by email until verified.
- Existing accounts are deliberately marked unverified by the additive migration. They can verify their current email from Account Settings while logged in.
- Existing users who already lost access and never verified email require manual support recovery with independent proof of ownership. Never silently mark old emails verified.
- Changing email requires the current password and verification of the new address. The old email remains until confirmation.
- Email verification invalidates remembered credentials, requiring a fresh login.
- Consider an expiration policy for unverified, unused registrations to prevent email squatting.

## Further staging review
- Apply migrations to a backup database first and verify all new columns and tables.
- Test simultaneous token submissions, email change races, delivery failure and account cooldown.
- Confirm active and parked game sessions disconnect after a reset.
- Test client links on the real /client/ route and React StrictMode.
- Review DNS, origin/CORS and Cloudflare/Railway proxy configuration.
- Build/test checks required. No production deployment has occurred.
