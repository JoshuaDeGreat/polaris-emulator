"""Unit tests using fixture ZIPs; no real network, database or deployment required."""
import argparse
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import habboassets_furni_import as imp


CLASS = 'demo_sofa'
PNG = b'\x89PNG\r\n\x1a\n' + b'0' * 50
ENTRY = {'classname': CLASS,'id': 809000,'name':'Demo Sofa','xdim':2,'ydim':1,'zdim':1,'canstandon':False,'cansiton':True,'canlayon':False,'type':'room'}


def make_zip(filename=CLASS+'.nitro', include_icon=True):
    content = io.BytesIO()
    with zipfile.ZipFile(content, 'w') as z:
        z.writestr('furniture/'+filename, b'nitrotestbundle')
        if include_icon:
            z.writestr('icons/'+CLASS+'_icon.png', PNG)
    return content.getvalue()


class ImportTests(unittest.TestCase):
    def test_select_nested_entry(self):
        actual = imp.select_entry({'data':{'furnidata': {'roomitemtypes': {'furnitype':[ENTRY]}}}}, CLASS)
        self.assertEqual(actual['id'], 809000)

    def test_duplicate_definitions_rejected(self):
        with self.assertRaises(imp.ImportErrorSafe):
            imp.validate_collisions([{'id':'999','sprite_id':'809000','item_name':'something_else'}], 810000, 809000, CLASS)
        with self.assertRaises(imp.ImportErrorSafe):
            imp.validate_collisions([{'id':'810000','sprite_id':'5','item_name':'something_else'}], 810000, 809000, CLASS)

    def test_zip_traversal_rejected(self):
        with self.assertRaises(imp.ImportErrorSafe):
            imp.parse_archive(make_zip('../../demo_sofa.nitro'), CLASS)

    def test_no_icon_fails(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            args=self.prepare_inputs(root, make_zip(include_icon=False))
            with self.assertRaisesRegex(imp.ImportErrorSafe, 'icon missing'):
                imp.prepare(args)
            self.assertFalse(args.output.exists())

    def test_success_dry_run_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            args=self.prepare_inputs(root, make_zip())
            imp.prepare(args)
            self.assertTrue((args.output / 'nitro-assets/bundled/furniture/demo_sofa.nitro').exists())
            self.assertTrue((args.output / 'swf/dcr/hof_furni/icons/demo_sofa_icon.png').exists())
            report=json.loads((args.output / 'review.json').read_text())
            self.assertEqual(report['status'], 'REVIEW_REQUIRED_NOT_DEPLOYED')
            sql=(args.output / 'import_review.sql').read_text()
            self.assertIn('INSERT INTO items_base',sql)
            self.assertIn('INSERT INTO catalog_items',sql)
            with self.assertRaises(imp.ImportErrorSafe):
                imp.prepare(args)

    def prepare_inputs(self, root, zip_bytes):
        items=root/'items.csv'
        items.write_text('id,sprite_id,item_name\n10,10,old_item\n')
        entry=root/'entry.json'
        entry.write_text(json.dumps(ENTRY))
        archive=root/'asset.zip'
        archive.write_bytes(zip_bytes)
        return argparse.Namespace(classname=CLASS,item_id=810000,page_id=12,credits=5,
           existing_items_csv=items,existing_furnidata=None,entry_json=entry,zip_file=archive,
           icon=None,wall=False,token_env='NO_TOKEN_TEST',output=root/'out')


if __name__ == '__main__':
    unittest.main()