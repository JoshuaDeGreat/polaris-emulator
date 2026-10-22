#!/usr/bin/env python3
"""Read-only audit of Heaven's original Wrangler static-asset source directories.

Does not download, deploy, or modify any Cloudflare Worker or Railway service.
Uses only Python's standard library. Run on the PC that holds Octane-Prepared.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def asset_count(root: Path) -> tuple[int, int]:
    count = size = 0
    if root.is_dir():
        for path in root.rglob("*"):
            if path.is_file() and not path.is_symlink():
                count += 1
                size += path.stat().st_size
    return count, size


def collect(root: Path, suffix: str) -> int:
    return sum(1 for p in root.glob("*") if p.is_file() and p.suffix.lower() == suffix) if root.is_dir() else 0


def audit(game_root: Path, icon_root: Path, classname: str | None = None) -> dict:
    errors: list[str] = []
    warnings: list[str] = []
    game = game_root / "client/nitro/bundled/furniture"
    icons = icon_root / "client/c_images/dcr/hof_furni/icons"
    data_file = game_root / "gamedata/FurnitureData.json"
    for label, path in (("Game Worker source", game_root), ("Icon Worker source", icon_root),
                        ("Furniture bundles", game), ("Furniture icons", icons)):
        if not path.is_dir() or path.is_symlink():
            errors.append(f"{label} directory missing or is a symlink: {path}")
    furniture = []
    if not data_file.is_file() or data_file.is_symlink():
        errors.append(f"Missing original FurnitureData.json: {data_file}")
    else:
        try:
            document = json.loads(data_file.read_text(encoding="utf-8-sig"))
            for section in ("roomitemtypes", "wallitemtypes"):
                node = document.get(section) if isinstance(document, dict) else None
                records = node.get("furnitype") if isinstance(node, dict) else None
                if not isinstance(records, list):
                    errors.append(f"FurnitureData missing {section}.furnitype array")
                else:
                    furniture.extend(records)
            if any(not isinstance(x, dict) for x in furniture):
                errors.append("FurnitureData includes an invalid record")
        except (ValueError, OSError, UnicodeError) as exc:
            errors.append(f"Could not read valid FurnitureData JSON: {type(exc).__name__}")
    n_bundles = collect(game, ".nitro")
    n_icons = collect(icons, ".png")
    if game.is_dir() and n_bundles == 0:
        errors.append("No .nitro furniture bundles found")
    if icons.is_dir() and n_icons == 0:
        errors.append("No PNG furniture icons found")
    if furniture and not n_bundles:
        warnings.append("FurnitureData exists, but no matching bundle directory content was found")
    if n_bundles < 50 or n_icons < 50:
        warnings.append("Asset inventory looks small for the previously deployed hotel; verify source completeness before publication")
    if classname:
        if not classname.isascii() or not classname.replace("_", "").replace("-", "").isalnum() or len(classname) > 70:
            errors.append("Invalid requested classname")
        else:
            if (game / f"{classname}.nitro").exists():
                warnings.append(f"Bundle already exists for {classname}; don't overwrite without a reviewed replacement")
            if (icons / f"{classname}_icon.png").exists():
                warnings.append(f"Icon already exists for {classname}; don't overwrite without a reviewed replacement")
            if any(isinstance(x, dict) and x.get("classname", "").casefold() == classname.casefold() for x in furniture):
                warnings.append(f"FurnitureData already contains classname {classname}")
    game_count, game_bytes = asset_count(game_root)
    icon_count, icon_bytes = asset_count(icon_root)
    return {
        "status": "SOURCE_LAYOUT_VALID_NOT_DEPLOY_VERIFIED" if not errors else "BLOCKED",
        "worker_names": ["octane-game-assets", "octane-furniture-icons"],
        "source_roots": {"game": str(game_root), "icons": str(icon_root)},
        "file_counts": {"game": game_count, "icons": icon_count,
                        "nitro_furniture_bundles": n_bundles, "png_furniture_icons": n_icons,
                        "furnidata_entries": len(furniture)},
        "total_bytes": {"game": game_bytes, "icons": icon_bytes},
        "furnidata_sha256": sha256(data_file) if data_file.is_file() and not data_file.is_symlink() else None,
        "errors": errors,
        "warnings": warnings,
        "deployment_approved": False,
        "source_matches_live_worker_proven": False,
        "live_database_checked": False,
        "message": "Local layout check only. Compare to original Wrangler deployment source/backup and validate staged DB before publishing; never deploy a partial overlay.",
    }


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--game-assets-root", type=Path, required=True)
    p.add_argument("--icons-root", type=Path, required=True)
    p.add_argument("--classname", help="Optional item to check against local assets")
    p.add_argument("--output", type=Path, help="New JSON report file, no overwrite")
    args = p.parse_args(argv)
    result = audit(args.game_assets_root, args.icons_root, args.classname)
    serialized = json.dumps(result, indent=2, ensure_ascii=False)
    if args.output:
        if args.output.exists():
            print("Report path already exists; refusing overwrite", file=sys.stderr)
            return 2
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)
    return 0 if not result["errors"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
