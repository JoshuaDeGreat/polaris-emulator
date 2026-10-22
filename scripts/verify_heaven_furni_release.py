#!/usr/bin/env python3
"""Offline preflight for a Heaven furni stage. No live changes, credentials, or network."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import sys

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def verify(package: Path, stage: Path) -> dict:
    problems = []
    def read(path):
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            problems.append(f"{path.name}: unreadable JSON ({type(e).__name__})")
            return {}
    pkg = read(package / "review.json")
    review = read(stage / "stage-review.json")
    classname = pkg.get("classname")
    if pkg.get("status") != "REVIEW_REQUIRED_NOT_DEPLOYED":
        problems.append("Import package not approved for review")
    if review.get("status") != "OVERLAY_PREPARED_NOT_DEPLOYED" or review.get("classname") != classname:
        problems.append("Staging report not matched to package")
    if not isinstance(classname, str) or not classname.isascii() or not classname.replace("_","").replace("-","").isalnum():
        problems.append("Invalid classname")
        classname = "INVALID"
    checksums = pkg.get("sha256")
    if not isinstance(checksums, dict):
        problems.append("Missing import checksums")
        checksums = {}
    for rel, expected in checksums.items():
        if not isinstance(rel,str) or rel.startswith("/") or ".." in Path(rel).parts or "\\" in rel:
            problems.append("Unsafe checksum entry")
            continue
        item = package / rel
        if not item.is_file() or item.is_symlink() or digest(item) != expected:
            problems.append(f"Package checksum mismatch: {rel}")
    game = stage / "DO_NOT_DEPLOY_ALONE_octane-game-assets"
    icons = stage / "DO_NOT_DEPLOY_ALONE_octane-furniture-icons"
    bundle = game / "client/nitro/bundled/furniture" / f"{classname}.nitro"
    icon = icons / "client/c_images/dcr/hof_furni/icons" / f"{classname}_icon.png"
    merged = game / "gamedata/FurnitureData.json"
    for path in [bundle, icon, merged]:
        if not path.is_file() or path.is_symlink():
            problems.append(f"Missing staged asset: {path.name}")
    for staged_path, source_path in [
        (bundle,package/"nitro-assets/bundled/furniture"/f"{classname}.nitro"),
        (icon,package/"swf/dcr/hof_furni/icons"/f"{classname}_icon.png")]:
        if staged_path.is_file() and source_path.is_file() and digest(staged_path) != digest(source_path):
            problems.append(f"Staged asset changed: {staged_path.name}")
    if merged.is_file():
        if digest(merged) != review.get("merged_furnidata_sha256"):
            problems.append("Staged FurnitureData checksum mismatch")
        data = read(merged)
        entries=[]
        for group in ("roomitemtypes","wallitemtypes"):
            section=data.get(group,{})
            if not isinstance(section,dict) or not isinstance(section.get("furnitype",[]),list):
                problems.append(f"Malformed {group} in merged FurnitureData")
            else:
                entries += section.get("furnitype",[])
        matches=[x for x in entries if isinstance(x,dict) and x.get("classname")==classname]
        if len(matches)!=1 or str(matches[0].get("id"))!=str(pkg.get("sprite_id")):
            problems.append("Exactly one matching classname and sprite ID required in staged FurnitureData")
    return {
      "result": "OFFLINE_CHECKS_PASSED_NOT_DEPLOY_APPROVAL" if not problems else "BLOCKED",
      "problems": problems,
      "live_db_checked":False,
      "live_worker_source_checked":False,
      "real_habboassets_download_verified":False,
      "permission_to_deploy":False
    }

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--package",required=True,type=Path)
    p.add_argument("--stage",required=True,type=Path)
    p.add_argument("--report",required=True,type=Path)
    args=p.parse_args()
    result=verify(args.package,args.stage)
    if args.report.exists():
        print("Refusing to overwrite existing report",file=sys.stderr)
        return 2
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    print(result["result"])
    return 0 if not result["problems"] else 1

if __name__=="__main__":
    raise SystemExit(main())
