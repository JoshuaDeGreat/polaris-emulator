"""Offline tests for local Wrangler asset source audit."""
from pathlib import Path
import json
import tempfile
import unittest
import audit_heaven_asset_sources as mod

class AssetSourceAuditTests(unittest.TestCase):
    def fixture(self, root):
        game=root/"game"; icons=root/"icons"
        bundle=game/"client/nitro/bundled/furniture"
        icon=icons/"client/c_images/dcr/hof_furni/icons"
        bundle.mkdir(parents=True); icon.mkdir(parents=True)
        (bundle/"old.nitro").write_bytes(b"old")
        (icon/"old_icon.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"0"*40)
        d=game/"gamedata";d.mkdir()
        (d/"FurnitureData.json").write_text(json.dumps({"roomitemtypes":{"furnitype":[{"classname":"old","id":1}]},"wallitemtypes":{"furnitype":[]}}))
        return game,icons
    def test_valid_layout_is_not_deployment_approval(self):
        with tempfile.TemporaryDirectory() as d:
            game,icons=self.fixture(Path(d))
            report=mod.audit(game,icons)
            self.assertEqual(report["status"],"SOURCE_LAYOUT_VALID_NOT_DEPLOY_VERIFIED")
            self.assertFalse(report["deployment_approved"])
            self.assertEqual(report["file_counts"]["nitro_furniture_bundles"],1)
    def test_missing_root_blocks(self):
        with tempfile.TemporaryDirectory() as d:
            r=mod.audit(Path(d)/"missing",Path(d)/"also_missing")
            self.assertEqual(r["status"],"BLOCKED")
    def test_existing_item_warns(self):
        with tempfile.TemporaryDirectory() as d:
            game,icons=self.fixture(Path(d))
            r=mod.audit(game,icons,"old")
            self.assertTrue(any("already contains" in x for x in r["warnings"]))
    def test_malformed_json_blocks(self):
        with tempfile.TemporaryDirectory() as d:
            game,icons=self.fixture(Path(d))
            (game/"gamedata/FurnitureData.json").write_text("{")
            self.assertEqual(mod.audit(game,icons)["status"],"BLOCKED")
if __name__=="__main__":unittest.main()
