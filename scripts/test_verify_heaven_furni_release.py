"""Offline validation tests for staged furniture. No external calls."""
import json
from pathlib import Path
import tempfile
import unittest
import stage_heaven_furni as stage
import verify_heaven_furni_release as verify
from test_stage_heaven_furni import StageTests

class PreflightTests(unittest.TestCase):
    def test_valid_package_stage_is_still_not_deployment_approved(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            package,game,icons=StageTests().make(root)
            out=root/"out"
            stage.stage(package,game/"gamedata/FurnitureData.json",game,icons,out)
            result=verify.verify(package,out)
            self.assertEqual(result["result"],"OFFLINE_CHECKS_PASSED_NOT_DEPLOY_APPROVAL")
            self.assertFalse(result["permission_to_deploy"])
    def test_changed_binary_blocks_preflight(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            package,game,icons=StageTests().make(root)
            out=root/"out"
            stage.stage(package,game/"gamedata/FurnitureData.json",game,icons,out)
            target=out/"DO_NOT_DEPLOY_ALONE_octane-game-assets/client/nitro/bundled/furniture/demo_sofa.nitro"
            target.write_bytes(b"tamper")
            result=verify.verify(package,out)
            self.assertEqual(result["result"],"BLOCKED")
    def test_missing_metadata_blocks_preflight(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            package,game,icons=StageTests().make(root)
            out=root/"out"
            stage.stage(package,game/"gamedata/FurnitureData.json",game,icons,out)
            (out/"DO_NOT_DEPLOY_ALONE_octane-game-assets/gamedata/FurnitureData.json").unlink()
            self.assertEqual(verify.verify(package,out)["result"],"BLOCKED")

if __name__=="__main__":
    unittest.main()
