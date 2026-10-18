# Internal guild badge imager

Clients such as Octane compose guild badges from their bundled badge parts. They do not need the legacy server-side guild PNG generator.

Set `IMAGER_INTERNAL_ENABLED=false` on those deployments to skip loading local badge-part files and generating guild PNGs. The override applies at startup, reload and generation. Unset it to keep the existing `imager.internal.enabled` database setting, or set it to `true` when server-generated PNG files are required. Accepted values are `true`, `false`, `1` and `0`.

This setting does not disable uploaded custom badges or change guild badge codes. Legacy clients that require server-generated PNGs must keep generation enabled, install their badge-part assets and configure a writable output directory.
