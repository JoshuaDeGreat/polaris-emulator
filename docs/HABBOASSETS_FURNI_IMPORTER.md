# Heaven Hotel: HabboAssets furniture importer (safe preparation tool)

This is an opt-in **review-and-stage** CLI for Heaven's Polaris + Octane setup. It does **not** write to Railway/MariaDB, commit migrations, edit Cloudflare Workers, or deploy files. Python 3.10+; no pip dependencies.

HabboAssets API v2: <https://www.habboassets.com/api>. Public `/api/v2/furniture/zip?classnames=...&format=nitro` can download a furniture bundle. Full `/api/v2/furniture/{classname}` metadata requires a free API token from HabboAssets. The token is read **only** from an environment variable and never saved in output.

## Before running

1. Back up the live hotel database and the exact currently deployed FurnitureData/assets.
2. Export a fresh **CSV** from your Railway MariaDB instance (e.g. with HeidiSQL):

   ```sql
   SELECT id, sprite_id, item_name FROM items_base ORDER BY id;
   ```

   Export with header `id,sprite_id,item_name` to `items_base.csv`. Keep the file private; this tool only reads it.
3. Find a **free** new `items_base.id` in that export; do not use the furniture's Habbo ID as the item definition ID by assumption. Check the target catalogue page ID with `SELECT id, caption FROM catalog_pages ORDER BY id;`.
4. Download **the actual transparent inventory PNG icon**, `<classname>_icon.png`, if the ZIP does not contain it. A preview render is not guaranteed to work as an inventory icon.
5. If possible, download/locate the **current live** `FurnitureData.json` to run the second collision check (`--existing-furnidata`). Split data must be checked against the merged full dataset before promotion.

## Windows PowerShell example

```powershell
$env:HABBOASSETS_TOKEN = Read-Host 'HabboAssets token' # set in this shell; never commit it
python scripts/habboassets_furni_import.py demo_sofa `
  --item-id 810000 `
  --page-id 12 `
  --credits 5 `
  --existing-items-csv .\items_base.csv `
  --existing-furnidata .\FurnitureData.json `
  --icon .\demo_sofa_icon.png `
  --output .\review-demo-sofa
Remove-Item Env:HABBOASSETS_TOKEN
```

Replace `demo_sofa`, IDs, files, and page with genuine data. **No particular ID or page above is verified free on your live hotel.** For wall items add `--wall`. Use `--entry-json path.json` for offline metadata and/or `--zip-file downloaded.zip` for offline binary bundles. `--entry-json` must contain a genuine complete furnidata entry (classname, id, xdim, ydim, canstandon, cansiton, canlayon) or a FurnitureData.json wrapper containing it. Do not fabricate metadata or use a normal preview PNG as an inventory icon.

## Outputs

- `nitro-assets/bundled/furniture/<classname>.nitro` — client graphics;
- `swf/dcr/hof_furni/icons/<classname>_icon.png` — inventory icon;
- `FurnitureData.entry.json` — isolated entry **to merge**, not a replacement full FurnitureData file;
- `import_review.sql` — SQL **draft only**, to review and adapt into a NEW immutable Flyway migration;
- `review.json` — SHA-256 checksums, collisions checked, and manual approval checklist.

**Do not blindly run `import_review.sql` on Railway.** Your production migration chain lives in `Emulator/src/main/resources/db/migration`, so review/merge as a new timestamped migration, never rewrite an existing one. Validate against the *fresh live data again* before applying anything: the importer cannot reserve IDs, and a database export is only a snapshot. Unusual interactive items (credit/NFT redemption, vending, pets, wired, recolour, etc.) need a specialist review rather than just a default item definition.

Merge into the **actual current asset source** behind the Cloudflare Workers. The Octane repository's example configuration currently references separate `octane-game-assets` and `octane-furniture-icons` Workers; they are **not** ordinary upload folders. Check the Workers' backing assets and MIME/CORS before publication.

Run the existing Polaris `docs/FURNI_IMPORT_PIPELINE.md` verification procedure on the merged staged assets and staged database, then test purchase, inventory icon, placement and re-entry. Only deploy after all checks pass and rights to use the assets are confirmed.

## Tests

```bash
cd scripts
python -m unittest test_habboassets_furni_import.py
```

Tests use synthetic data. They do not call HabboAssets or access a live hotel. First production use still requires a real API/asset-source integration test.