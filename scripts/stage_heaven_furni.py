#!/usr/bin/env python3
"""Prepare non-deployable Cloudflare Worker asset overlays for a reviewed Polaris furniture import.

Never connects to network, modifies the live DB, or publishes to Cloudflare.
The output directories are INCOMPLETE Worker assets. Merge them into verified,
complete existing Worker source directories before any Wrangler deployment.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile

SAFE_NAME = re.compile(r'^[A-Za-z0-9_-]{1,70}$')


class StageError(Exception):
    pass


def sha256(path: Path) -> str:
    with path.open('rb') as stream:
        import hashlib
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding='utf-8-sig'))
    except (OSError, ValueError) as exc:
        raise StageError(f'Cannot read {path.name} as JSON: {exc}') from None
    if not isinstance(value, dict):
        raise StageError(f'{path.name}: expected JSON object')
    return value


def get_entries(data: dict, section: str) -> list:
    area = data.get(section)
    if not isinstance(area, dict) or not isinstance(area.get('furnitype'), list):
        raise StageError(f'FurnitureData requires {section}.furnitype as a list')
    return area['furnitype']


def stage(package: Path, furniture_data: Path, game_assets_root: Path,
          icons_root: Path, output: Path) -> dict:
    if output.exists():
        raise StageError('Output directory already exists; never overwrite a prior stage')
    report = read_json(package / 'review.json')
    if report.get('status') != 'REVIEW_REQUIRED_NOT_DEPLOYED':
        raise StageError('Package was not produced by the reviewed Heaven importer')
    classname = report.get('classname', '')
    if not isinstance(classname, str) or not SAFE_NAME.fullmatch(classname):
        raise StageError('Invalid furniture classname in import report')
    source_bundle = package / 'nitro-assets' / 'bundled' / 'furniture' / f'{classname}.nitro'
    source_icon = package / 'swf' / 'dcr' / 'hof_furni' / 'icons' / f'{classname}_icon.png'
    entry_doc = read_json(package / 'FurnitureData.entry.json')
    sections = [s for s in ('roomitemtypes', 'wallitemtypes') if s in entry_doc]
    if len(sections) != 1:
        raise StageError('Import must contain exactly one room/wall section')
    section = sections[0]
    incoming = get_entries(entry_doc, section)
    if len(incoming) != 1 or not isinstance(incoming[0], dict):
        raise StageError('Import must contain exactly one furniture definition')
    entry = incoming[0]
    if entry.get('classname') != classname or str(entry.get('id')) != str(report.get('sprite_id')):
        raise StageError('FurnitureData metadata does not match import report')
    checksums = report.get('sha256')
    if not isinstance(checksums, dict):
        raise StageError('Import package is missing checksums')
    for p in (source_bundle, source_icon, package / 'FurnitureData.entry.json'):
        key = p.relative_to(package).as_posix()
        if not p.is_file() or p.is_symlink() or sha256(p) != checksums.get(key):
            raise StageError(f'Missing or modified file: {key}')
    if source_icon.read_bytes()[:8] != b'\x89PNG\r\n\x1a\n':
        raise StageError('Missing PNG icon header')
    if not source_bundle.stat().st_size:
        raise StageError('Furniture bundle is empty')
    # Exact URL paths from Octane's renderer-config.json.example.json:
    rel_bundle = Path('client/nitro/bundled/furniture') / f'{classname}.nitro'
    rel_icon = Path('client/c_images/dcr/hof_furni/icons') / f'{classname}_icon.png'
    rel_data = Path('gamedata/FurnitureData.json')
    for label, root, rel in [('bundle', game_assets_root, rel_bundle), ('icon', icons_root, rel_icon)]:
        if not root.is_dir():
            raise StageError(f'{label}: source Worker asset directory is missing')
        if (root / rel).exists():
            raise StageError(f'{label}: already exists in source Worker assets: {rel}')
    if not furniture_data.is_file() or furniture_data.is_symlink():
        raise StageError('Existing full FurnitureData.json must be supplied')
    # Source must point at the verified existing deployed FurnitureData.json from the game assets tree.
    if furniture_data.resolve() != (game_assets_root / rel_data).resolve():
        raise StageError('FurnitureData path must be game-assets-root/gamedata/FurnitureData.json')
    current = read_json(furniture_data)
    current_entries = []
    for kind in ('roomitemtypes', 'wallitemtypes'):
        if kind in current:
            current_entries.extend(get_entries(current, kind))
    for old in current_entries:
        if not isinstance(old, dict):
            raise StageError('Existing FurnitureData has a malformed furniture record')
        if str(old.get('id')) == str(entry['id']) or str(old.get('classname', '')).casefold() == classname.casefold():
            raise StageError('FurnitureData already contains the sprite ID or classname')
    if section not in current:
        current[section] = {'furnitype': []}
    get_entries(current, section).append(entry)
    # Copy *only* new assets and merged metadata to an isolated overlay. Do not call wrangler on this overlay.
    with tempfile.TemporaryDirectory(prefix='heaven-stage-') as td:
        temp = Path(td) / 'stage'
        game = temp / 'DO_NOT_DEPLOY_ALONE_octane-game-assets'
        icons = temp / 'DO_NOT_DEPLOY_ALONE_octane-furniture-icons'
        for source, destination in [(source_bundle, game / rel_bundle), (source_icon, icons / rel_icon)]:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        target = game / rel_data
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(current, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
        result = {
            'status': 'OVERLAY_PREPARED_NOT_DEPLOYED',
            'classname': classname,
            'sprite_id': entry['id'],
            'source_furnidata_sha256': sha256(furniture_data),
            'merged_furnidata_sha256': sha256(target),
            'existing_furnidata_records': len(current_entries),
            'new_asset_paths': [rel_bundle.as_posix(), rel_icon.as_posix()],
            'warning': 'Never deploy these partial overlay directories as complete Cloudflare Workers! Merge each into a complete backed-up source tree, run Polaris validator, then deploy deliberately.',
            'next_checks': ['Recheck live database ID collisions', 'Compare full Worker source file inventory before/after', 'Validate item interactions and rights', 'Run scripts/verify-furni-import on staging', 'Test catalogue purchase, image, placement, reload']
        }
        (temp / 'stage-review.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(temp, output)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--game-assets-root', type=Path, required=True, help='Verified complete local source for octane-game-assets Worker')
    parser.add_argument('--icons-root', type=Path, required=True, help='Verified complete local source for octane-furniture-icons Worker')
    parser.add_argument('--output', type=Path, required=True, help='New directory for NON-DEPLOYABLE overlays')
    args = parser.parse_args(argv)
    try:
        result = stage(args.package, args.game_assets_root / 'gamedata/FurnitureData.json', args.game_assets_root, args.icons_root, args.output)
    except (StageError, OSError, KeyError, ValueError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 1
    print(f"Prepared staged assets for {result['classname']} in {args.output} (NOT deployed)")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
