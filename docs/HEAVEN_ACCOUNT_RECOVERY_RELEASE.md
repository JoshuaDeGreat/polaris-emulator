# Heaven Hotel account recovery release

Recovery staging: https://recovery-polaris-staging.up.railway.app
Client build staging: https://recovery-octane-staging.up.railway.app

The isolated database contains no production player data. The sending key is restricted to heavenhotel.uk and stored in service variables. Production RESEND_API_KEY and SMTP_PROVIDER changes are staged, not yet applied.

Pair the current recovery client branch with JoshuaDeGreat/Octane-Renderer main (9caa9eb); the matching recovery renderer branch contains the same source and updated packet contracts. Preserve current catalogue fixes. Final live mail settings must use https://play.heavenhotel.uk/?recovery=1 and ?verify=1.
