# Heaven Hotel password recovery — staging checklist

This feature is intentionally developed on `feature/heaven-password-recovery` while Railway builds are paused. Do not merge until both repositories are deployed together and tested.

## Already present
- Octane login includes Forgot Password, which posts to `/api/auth/forgot-password`.
- Polaris stores registration emails in `users.mail` and bcrypt hashes in `users.password`.
- Polaris already creates rows in `password_resets`, with a runtime schema repair migration.

## New flow
1. Forgot Password generates a 32-byte random token, stores only its SHA-256 digest and expires it in 20 minutes.
2. Resend sends the link to the Octane client recovery screen.
3. Octane posts `token`, `newPassword`, and `confirmPassword` to `/api/auth/reset-password`.
4. Polaris locks the token row, verifies expiry, bcrypt-hashes the new password, revokes remember-token families, invalidates access tokens and SSO ticket, deletes the used token and commits atomically.

## Required deployment settings
Add these settings to Polaris configuration via a secure secrets mechanism (do not commit a real key):
- `smtp.provider=resend`
- `resend.api_key=<Resend sending API key>`
- `smtp.from_address=accounts@heavenhotel.uk`
- `smtp.from_name=Heaven Hotel`
- `password.reset.url=https://heavenhotel.uk/client/?recovery=1`

Verify the actual public client URL and override `password.reset.url` if different. The query must include `recovery=1`, since Polaris appends `&token=...` to the configured URL. The client must resolve its `login.reset.endpoint` to the Polaris API, particularly when the API and client are hosted on different origins.

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

## Follow-up hardening
- Add ownership verification for registration and email changes; currently entering an email is not proof of control.
- Add per-account cooldown and delivery-failure handling so email floods do not invalidate recovery links repeatedly.
- Consider disconnecting active game sessions after successful reset.
