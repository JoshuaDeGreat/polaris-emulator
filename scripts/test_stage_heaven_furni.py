"""Offline tests for Heaven furniture staging. No production writes."""
import json
from pathlib import Path
import tempfile
import unittest

import stage_heaven_furni as stage

CLASS='demo_sofa'
ENTRY={'classname':CLASS,'id':809000,'xdim':1,'ydim':1,'canstandon':0,'cansiton':1,'canlayon':0}

class StageTests(unittest.TestCase):
    def make(self, root):
        package=root/'package'
        b=package/'nitro-assets/bundled/furniture/demo_sofa.nitro'
        i=package/'swf/dcr/hof_furni/icons/demo_sofa_icon.png'
        j=package/'FurnitureData.entry.json'
        for p in (b,i,j):p.parent.mkdir(parents=True,exist_ok=True)
        b.write_bytes(b'nitrofake')
        i.write_bytes(b'\x89PNG\r\n\x1a\n' + b'pngfake')
        j.write_text(json.dumps({'roomitemtypes':{'furnitype':[ENTRY]}}))
        hashlist={p.relative_to(package).as_posix():stage.sha256(p) for p in (b,i,j)}
        (package/'review.json').write_text(json.dumps({'status':'REVIEW_REQUIRED_NOT_DEPLOYED','classname':CLASS,'sprite_id':809000,'sha256':hashlist}))
        game=root/'game'
        icons=root/'icons'
        (game/'gamedata').mkdir(parents=True)
        icons.mkdir()
        (game/'gamedata/FurnitureData.json').write_text(json.dumps({'roomitemtypes':{'furnitype':[{'classname':'old','id':12}]} ,'wallitemtypes':{'furnitype':[]}}))
        return package,game,icons
    def test_overlay_built_and_preserves_baseline(self):
        with tempfile.TemporaryDirectory() as td:
            p,g,i=self.make(Path(td))
            result=stage.stage(p,g/'gamedata/FurnitureData.json',g,i,Path(td)/'stage')
            self.assertEqual(result['status'],'OVERLAY_PREPARED_NOT_DEPLOYED')
            target=Path(td)/'stage/DO_NOT_DEPLOY_ALONE_octane-game-assets/gamedata/FurnitureData.json'
            entries=json.loads(target.read_text())['roomitemtypes']['furnitype']
            self.assertEqual([x['classname'] for x in entries],['old',CLASS])
            original=json.loads((g/'gamedata/FurnitureData.json').read_text())['roomitemtypes']['furnitype']
            self.assertEqual(len(original),1)
    def test_existing_sprite_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            p,g,i=self.make(Path(td))
            (g/'gamedata/FurnitureData.json').write_text(json.dumps({'roomitemtypes':{'furnitype':[{'classname':'else','id':809000}]}}))
            with self.assertRaisesRegex(stage.StageError,'already contains'):
                stage.stage(p,g/'gamedata/FurnitureData.json',g,i,Path(td)/'out')
    def test_existing_binary_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            p,g,i=self.make(Path(td))
            existing=g/'client/nitro/bundled/furniture/demo_sofa.nitro'
            existing.parent.mkdir(parents=True)
            existing.write_bytes(b'old')
            with self.assertRaisesRegex(stage.StageError,'already exists'):
                stage.stage(p,g/'gamedata/FurnitureData.json',g,i,Path(td)/'out')
    def test_tampering_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            p,g,i=self.make(Path(td))
            (p/'nitro-assets/bundled/furniture/demo_sofa.nitro').write_bytes(b'tampered')
            with self.assertRaisesRegex(stage.StageError,'modified file'):
                stage.stage(p,g/'gamedata/FurnitureData.json',g,i,Path(td)/'out')
    def test_existing_stage_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            p,g,i=self.make(Path(td))
            o=Path(td)/'stage';o.mkdir()
            with self.assertRaisesRegex(stage.StageError,'already exists'):
                stage.stage(p,g/'gamedata/FurnitureData.json',g,i,o)
if __name__=='__main__': unittest.main()
