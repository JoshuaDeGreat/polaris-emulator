#!/usr/bin/env python3
"""Prepare a reviewable HabboAssets furniture import for Polaris + Octane.

This command NEVER modifies the live hotel, database, Workers, or deployed assets.
Python 3.10+, standard library only.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPRedirectHandler
import zipfile

API_ORIGIN = 'https://www.habboassets.com'
MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024
MAX_UNPACKED_BYTES = 100 * 1024 * 1024
VALID_CLASSNAME = re.compile(r'^[a-zA-Z0-9_\-]{1,100}$')


class ImportErrorSafe(Exception):
    """Expected, actionable failure that can be shown without secrets."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ImportErrorSafe('Unexpected download redirect; refusing to forward credentials or trust new host')


def download(path: str, *, token: str = '', binary: bool = False) -> bytes:
    # Only access a fixed known API host; never follow server-supplied URLs.
    if not path.startswith('/api/v2/'):
        raise ImportErrorSafe('Invalid API path')
    headers = {'User-Agent': 'HeavenHotel-FurniImporter/1.0', 'Accept': 'application/zip' if binary else 'application/json'}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    request = Request(API_ORIGIN + path, headers=headers)
    try:
        with build_opener(NoRedirect()).open(request, timeout=25) as response:
            content_length = int(response.headers.get('Content-Length', '0') or '0')
            if content_length > MAX_DOWNLOAD_BYTES:
                raise ImportErrorSafe('Download exceeds 50 MiB limit')
            payload = response.read(MAX_DOWNLOAD_BYTES + 1)
            if len(payload) > MAX_DOWNLOAD_BYTES:
                raise ImportErrorSafe('Download exceeds 50 MiB limit')
            return payload
    except HTTPError as exc:
        if exc.code in (401, 403):
            raise ImportErrorSafe('HabboAssets metadata API requires a free token; set HABBOASSETS_TOKEN') from None
        raise ImportErrorSafe(f'HabboAssets HTTP {exc.code}') from None
    except (URLError, TimeoutError) as exc:
        raise ImportErrorSafe(f'Could not reach HabboAssets: {type(exc).__name__}') from None


def load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding='utf-8-sig'))
    except (OSError, ValueError) as exc:
        raise ImportErrorSafe(f'Cannot read JSON {path}: {exc}') from None


def flatten_entries(value):
    """Yield candidate furni records from API variants / standard FurnitureData."""
    if isinstance(value, list):
        for part in value:
            yield from flatten_entries(part)
    elif isinstance(value, dict):
        if isinstance(value.get('classname'), str) and ('id' in value or 'furni_id' in value):
            yield value
        for name, child in value.items():
            if name != 'revisions' and isinstance(child, (list, dict)):
                yield from flatten_entries(child)


def select_entry(document, classname: str) -> dict:
    matches = [entry for entry in flatten_entries(document) if entry.get('classname') == classname]
    if not matches:
        raise ImportErrorSafe(f'No exact FurnitureData record for {classname}. Supply --entry-json (a JSON entry with classname, id, xdim, ydim, flags).')
    # Prefer the most detailed furniture data record if the API wraps it in another summary.
    matches.sort(key=lambda x: sum(k in x for k in ('xdim', 'ydim', 'canstandon', 'cansiton', 'canlayon')), reverse=True)
    selected = dict(matches[0])
    if 'id' not in selected and 'furni_id' in selected:
        selected['id'] = selected.pop('furni_id')
    required = ('id', 'xdim', 'ydim', 'canstandon', 'cansiton', 'canlayon')
    missing = [key for key in required if key not in selected]
    if missing:
        raise ImportErrorSafe('Incomplete FurnitureData: missing ' + ', '.join(missing) + '; supply a complete --entry-json')
    return selected


def as_bool(value, name: str) -> int:
    if value in (True, 1, '1', 'true', 'True'):
        return 1
    if value in (False, 0, '0', 'false', 'False'):
        return 0
    raise ImportErrorSafe(f'Invalid boolean {name}: {value!r}')


def positive_int(value, name: str, *, allow_zero=False) -> int:
    try:
        num = int(value)
    except (ValueError, TypeError):
        raise ImportErrorSafe(f'Invalid integer for {name}') from None
    if not (0 if allow_zero else 1) <= num <= 2_000_000_000:
        raise ImportErrorSafe(f'{name} must be a positive 32-bit integer')
    return num


def safe_sql(text: str) -> str:
    return "'" + text.replace('\\', '\\\\').replace("'", "''") + "'"


def load_existing_rows(path: Path) -> list[dict]:
    try:
        with path.open(encoding='utf-8-sig', newline='') as f:
            reader = csv.DictReader(f)
            if not {'id', 'sprite_id', 'item_name'}.issubset(set(reader.fieldnames or [])):
                raise ImportErrorSafe('Existing items CSV needs headers: id,sprite_id,item_name')
            return list(reader)
    except OSError as exc:
        raise ImportErrorSafe(f'Cannot read existing items CSV: {exc}') from None


def validate_collisions(rows: list[dict], item_id: int, sprite_id: int, classname: str):
    for r in rows:
        if str(r['id']).strip() == str(item_id):
            raise ImportErrorSafe(f'items_base.id {item_id} is already taken')
        if str(r['sprite_id']).strip() == str(sprite_id):
            raise ImportErrorSafe(f'items_base.sprite_id {sprite_id} is already taken')
        if r['item_name'].strip().casefold() == classname.casefold():
            raise ImportErrorSafe(f'items_base already has classname {classname}')


def parse_archive(data: bytes, classname: str) -> tuple[bytes, bytes | None]:
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        raise ImportErrorSafe('Downloaded furniture package is not a ZIP') from None
    with z:
        total = 0
        bundle = None
        icon = None
        for f in z.infolist():
            # Even though files are read and not extracted, validate archive paths and types.
            pure = PurePosixPath(f.filename.replace('\\', '/'))
            if pure.is_absolute() or '..' in pure.parts or ':' in f.filename or f.filename.startswith(('\\', '/')):
                raise ImportErrorSafe(f'Unsafe ZIP path: {f.filename}')
            if (f.external_attr >> 16) & 0o170000 == 0o120000:
                raise ImportErrorSafe('ZIP contains symlink; rejecting')
            total += f.file_size
            if total > MAX_UNPACKED_BYTES:
                raise ImportErrorSafe('ZIP uncompressed contents exceed 100 MiB')
            if f.is_dir():
                continue
            name = pure.name
            if name == f'{classname}.nitro':
                if bundle is not None:
                    raise ImportErrorSafe('Duplicate furniture bundle in ZIP')
                bundle = z.read(f)
            if name == f'{classname}_icon.png':
                icon = z.read(f)
        if not bundle:
            raise ImportErrorSafe(f'ZIP does not contain {classname}.nitro')
        return bundle, icon


def png_ok(data: bytes) -> bool:
    return len(data) > 32 and data.startswith(b'\x89PNG\r\n\x1a\n')


def make_sql(args, item: dict, sprite_id: int, item_type: str) -> str:
    # SQL is a *reviewable draft* to convert to a Flyway migration after rechecking the live DB.
    columns = ['id','sprite_id','public_name','item_name','type','width','length','stack_height','allow_sit','allow_lay','allow_walk','interaction_type']
    name = str(item.get('name') or args.classname)
    if len(name) > 56:
        raise ImportErrorSafe('Furniture public_name exceeds Polaris 56-character limit')
    if len(args.classname) > 70:
        raise ImportErrorSafe('Furniture classname exceeds Polaris item_name limit (70)')
    x, y = positive_int(item['xdim'], 'xdim'), positive_int(item['ydim'], 'ydim')
    if x > 100 or y > 100:
        raise ImportErrorSafe('Furniture dimensions are implausible')
    height = item.get('zdim', 0)
    try:
        h = float(height)
    except (ValueError, TypeError):
        raise ImportErrorSafe('Invalid zdim') from None
    if h < 0 or h > 99.99:
        raise ImportErrorSafe('zdim is out of Polaris stack_height range')
    values = [str(args.item_id), str(sprite_id), safe_sql(name), safe_sql(args.classname), safe_sql(item_type),str(x),str(y),f'{h:.2f}',str(as_bool(item['cansiton'], 'cansiton')),str(as_bool(item['canlayon'], 'canlayon')),str(as_bool(item['canstandon'], 'canstandon')),"'default'"]
    return f'''-- GENERATED REVIEW DRAFT. NOT APPLIED.\n-- Verify collisions and catalog page against the *live* database again before use.\n-- For production, copy this into a NEW timestamped Flyway migration after review.\n-- IMPORTANT: non-default interactions (NFT, credit, vending, pets, wired, etc.) require manual implementation.\n-- Read-only recheck:\n-- SELECT id, sprite_id, item_name FROM items_base WHERE id = {args.item_id} OR sprite_id = {sprite_id} OR item_name = {safe_sql(args.classname)};\n-- SELECT id FROM catalog_pages WHERE id = {args.page_id};\nSTART TRANSACTION;\nINSERT INTO items_base ({', '.join('`'+s+'`' for s in columns)})\nVALUES ({', '.join(values)});\nINSERT INTO catalog_items (`item_ids`, `page_id`, `catalog_name`, `cost_credits`, `order_number`)\nVALUES ({safe_sql(str(args.item_id))}, {args.page_id}, {safe_sql(args.classname)}, {args.credits}, 1);\nCOMMIT;\n'''


def prepare(args):
    if not VALID_CLASSNAME.fullmatch(args.classname) or len(args.classname) > 70:
        raise ImportErrorSafe('Classname must be 1–70 letters, numbers, underscore or hyphen (no colour * variants yet)')
    item_id = positive_int(args.item_id, '--item-id')
    page_id = positive_int(args.page_id, '--page-id')
    credits = positive_int(args.credits, '--credits', allow_zero=True)
    rows = load_existing_rows(args.existing_items_csv)
    token = os.environ.get(args.token_env, '').strip()
    if args.entry_json:
        document = load_json(args.entry_json)
    else:
        if not token:
            raise ImportErrorSafe(f'No token in {args.token_env}; supply --entry-json with complete furniture metadata or set the environment variable')
        raw = download('/api/v2/furniture/' + args.classname, token=token)
        try:
            document = json.loads(raw)
        except ValueError:
            raise ImportErrorSafe('Metadata API did not return JSON') from None
    entry = select_entry(document, args.classname)
    sprite_id = positive_int(entry['id'], 'FurnitureData.id')
    item_type = 'i' if args.wall else 's'
    if args.wall and entry.get('type') in ('room', 'floor'):
        raise ImportErrorSafe('Wall import selected for a floor item')
    if not args.wall and entry.get('type') in ('wall', 'wallitem'):
        raise ImportErrorSafe('Floor import selected for a wall item; use --wall')
    validate_collisions(rows, item_id, sprite_id, args.classname)
    if args.existing_furnidata:
        for old in flatten_entries(load_json(args.existing_furnidata)):
            if old.get('classname') == args.classname or str(old.get('id')) == str(sprite_id):
                raise ImportErrorSafe('FurnitureData entry/classname or sprite ID already exists in existing file')
    if args.zip_file:
        try:
            archive_data = args.zip_file.read_bytes()
        except OSError as exc:
            raise ImportErrorSafe(f'Cannot read --zip-file: {exc}') from None
    else:
        archive_data = download('/api/v2/furniture/zip?' + urlencode({'classnames': args.classname, 'format': 'nitro'}), binary=True)
    bundle, embedded_icon = parse_archive(archive_data, args.classname)
    icon = args.icon.read_bytes() if args.icon else embedded_icon
    if not icon or not png_ok(icon):
        raise ImportErrorSafe('Valid PNG furniture icon missing. Supply --icon path/to/classname_icon.png (preview render is not the inventory icon).')
    if args.output.exists():
        raise ImportErrorSafe(f'Output already exists: {args.output}. Choose a new empty folder; never overwrite existing import files')
    # All I/O and validation above completed before creating output.
    with tempfile.TemporaryDirectory(prefix='heaven-furni-') as td:
        staging = Path(td) / 'package'
        asset_bundle = staging / 'nitro-assets' / 'bundled' / 'furniture' / f'{args.classname}.nitro'
        asset_icon = staging / 'swf' / 'dcr' / 'hof_furni' / 'icons' / f'{args.classname}_icon.png'
        patch = staging / 'FurnitureData.entry.json'
        sql_path = staging / 'import_review.sql'
        report = staging / 'review.json'
        for p in (asset_bundle, asset_icon, patch, sql_path, report):
            p.parent.mkdir(parents=True, exist_ok=True)
        asset_bundle.write_bytes(bundle)
        asset_icon.write_bytes(icon)
        patch.write_text(json.dumps({'wallitemtypes' if args.wall else 'roomitemtypes': {'furnitype': [entry]}}, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        sql_path.write_text(make_sql(args, entry, sprite_id, item_type), encoding='utf-8')
        checksums = {str(p.relative_to(staging)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (asset_bundle,asset_icon,patch,sql_path)}
        report.write_text(json.dumps({'status':'REVIEW_REQUIRED_NOT_DEPLOYED','classname':args.classname,'item_id':item_id,'sprite_id':sprite_id,'catalog_page_id':page_id,'credits':credits,'source':'HabboAssets API v2','checked_existing_items':len(rows),'existing_furnidata_checked':bool(args.existing_furnidata),'manual_steps':['Verify live items_base collisions and page ID again','Review special furniture interactions and redistribution rights','Merge this FurnitureData.entry.json into currently deployed FurnitureData or custom tier','Publish both binary assets to the source served by Cloudflare Workers','Review SQL and commit as a new immutable Flyway migration','Run scripts/verify-furni-import against staged actual assets and DB','Test purchase, inventory icon, placement, rotations, and reload before production'], 'sha256':checksums}, indent=2) + '\n', encoding='utf-8')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(staging, args.output)
    print(f'Prepared {args.classname} in {args.output} (NOT deployed). Review review.json and import_review.sql.')


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('classname', help='Exact HabboAssets furniture classname')
    p.add_argument('--item-id', type=int, required=True, help='New, unused Polaris items_base.id (NOT the Habbo furniture ID)')
    p.add_argument('--page-id', type=int, required=True, help='Existing Polaris catalog_pages.id')
    p.add_argument('--credits', type=int, default=3, help='Catalogue cost in credits (default 3)')
    p.add_argument('--existing-items-csv', type=Path, required=True, help='Current items_base export containing id,sprite_id,item_name')
    p.add_argument('--existing-furnidata', type=Path, help='Current FurnitureData.json to check duplicate id/classname')
    p.add_argument('--entry-json', type=Path, help='Offline furniture data JSON entry, if API token not available')
    p.add_argument('--zip-file', type=Path, help='Offline HabboAssets .nitro ZIP, if download not available')
    p.add_argument('--icon', type=Path, help='Exact transparent PNG inventory icon if ZIP lacks one')
    p.add_argument('--wall', action='store_true', help='Import a wall item instead of a floor item')
    p.add_argument('--token-env', default='HABBOASSETS_TOKEN', help='Environment variable holding API token')
    p.add_argument('--output', type=Path, required=True, help='New output directory (must not exist)')
    args = p.parse_args(argv)
    try:
        prepare(args)
    except (ImportErrorSafe, OSError, ValueError, TypeError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())