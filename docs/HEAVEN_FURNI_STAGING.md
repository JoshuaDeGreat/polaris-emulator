# Heaven Hotel: Phase 2 staging workflow

This tool prepares **non-deployable overlays** from a previously reviewed HabboAssets import package. It doesn't alter Cloudflare Workers, Railway MariaDB, existing local source assets or Octane/Polaris production services.

## Requirements
- A reviewed output from `scripts/habboassets_furni_import.py` containing `review.json`, `FurnitureData.entry.json`, `.nitro` bundle and icon
- A **complete, verified backup** of the currently deployed `octane-game-assets` Worker source directory (including `gamedata/FurnitureData.json`)
- A **complete, verified backup** of `octane-furniture-icons` Worker source directory

From the repo root:

```powershell
python scripts/stage_heaven_furni.py `
  --package .\review-demo-sofa `
  --game-assets-root .\existing-octane-game-assets `
  --icons-root .\existing-octane-furniture-icons `
  --output .\review-stage-demo-sofa
```

The result has two deliberately named `DO_NOT_DEPLOY_ALONE_*` asset overlay folders and `stage-review.json`. Review and merge these into **complete** existing Worker deployment sources. **Never deploy an overlay directly**: a full Worker static-assets publish with only new files can omit previous furniture. Preserve original assets, their names and MIME types.

The tool checks that the new sprite ID/classname are absent from the existing complete FurnitureData; rejects duplicate paths in backed-up assets; checks exact new-file hashes against the import review; leaves the original full FurnitureData unchanged; generates a merged copy for review.

## Outstanding before a live furniture import
1. Determine and back up the canonical sources from which the current Workers were uploaded (not just the Worker names).
2. Obtain an actual HabboAssets item package, its valid inventory icon and complete FurnitureData entry; verify use rights. A live HabboAssets download has **not** yet been verified.
3. Export current production `items_base` data and check all ID/sprite/classname conflicts and target catalogue page IDs. Do not assume demo IDs are available.
4. Confirm currently deployed Octane renderer runtime config (not only repository examples), including whether it uses split FurnitureData or `FurnitureData.json`; this script supports the latter only.
5. Merge into an offline complete source tree and run `scripts/verify-furni-import` against the merged assets, staged database and current complete FurnitureData.
6. Have a reviewed new Flyway migration, a rollback plan, and a test item purchase/placement/re-entry before publishing to production.

Tests: `cd scripts; python -m unittest test_habboassets_furni_import test_stage_heaven_furni`. The 10 tests run entirely offline, with synthetic fixtures.


## Offline release preflight and CI

After staging, run:

```powershell
python scripts/verify_heaven_furni_release.py `
  --package .\review-demo-sofa `
  --stage .\review-stage-demo-sofa `
  --report .\review-demo-sofa-preflight.json
```

The result `OFFLINE_CHECKS_PASSED_NOT_DEPLOY_APPROVAL` **does not authorize publication**. The script verifies the package hashes, staged asset copies, merged FurnitureData hash and classname/sprite correspondence, but makes **no live Railway or Cloudflare checks**. A `BLOCKED` result must be investigated, not bypassed.

The dedicated `.github/workflows/heaven-furni-importer.yml` workflow runs isolated Python syntax and tests on PRs, pushes to `main`, and manual dispatch. GitHub workflow checks are separate from validating a *real* furniture bundle and the user's canonical complete static-asset sources. Do not expose HabboAssets API tokens as build artifacts or PR comments.

## Asset-source discovery limitation

The connected Cloudflare API lists `octane-game-assets` and `octane-furniture-icons` Workers, but this does not establish that a reconstructable full local static-asset source directory exists. The workers' script-content read endpoint returned no script content for these Workers in the connected API. Do not assume we can safely publish a partial asset overlay or recover missing existing files via a Worker listing. Obtain the actual complete original deployment directories (or an integrity-verified backup) before a public publish.


## Wrangler source discovery (read-only)

Cloudflare confirms `octane-game-assets` and `octane-furniture-icons` were published as static-only Workers using Wrangler on 18 October 2026. Both versions have CORS `Access-Control-Allow-Origin: *`. This verifies their deployment type, **not** that the original complete upload directories are recoverable from the Worker API.

On the Windows PC used for the original uploads, find the original full directories, then run:

```powershell
python scripts/audit_heaven_asset_sources.py `
  --game-assets-root "C:\FULL\PATH\TO\GAME-ASSET-DIRECTORY" `
  --icons-root "C:\FULL\PATH\TO\ICON-DIRECTORY" `
  --output ".\heaven-asset-source-audit.json"
```

Replace the placeholder directories; none are known to be the canonical source yet. The audit reports total files and bytes, bundle and icon counts, and a FurnitureData SHA-256. The status `SOURCE_LAYOUT_VALID_NOT_DEPLOY_VERIFIED` verifies *local structure only*. It must **never** be interpreted as proof the directories match the complete live Worker asset manifests. Do not run `wrangler deploy` based solely on this report.
