#!/usr/bin/env python3
"""Exercise the catalogue repair SQL on an isolated MariaDB bootstrap datadir.

Usage: test-catalog-repairs.py /path/to/mariadbd /path/to/usr /path/to/test-datadir
The datadir must be disposable and already initialized; no network is used.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "Emulator/src/main/resources/db/migration"
SOURCE = (MIGRATIONS / "V20260518000000__base_database.sql").read_text()
REPAIRS = [
    (MIGRATIONS / "V20261020190000__repair_catalog_order_and_rosegold_balloon.sql").read_text(),
    (MIGRATIONS / "V20261020191000__restore_stock_room_bundle_templates.sql").read_text(),
]


def fixture(database):
    statements = [f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;", f"USE {database};", "SET FOREIGN_KEY_CHECKS=0;",
                  "CREATE TABLE users (id INT PRIMARY KEY, username VARCHAR(25), `rank` INT);",
                  "INSERT INTO users VALUES (99,'RepairTestAdmin',7);",
                  "CREATE TABLE catalog_id_sequences (entity_type VARCHAR(16), catalog_type VARCHAR(16), next_id BIGINT, PRIMARY KEY(entity_type,catalog_type));"]
    for table in ["catalog_pages", "catalog_pages_bc", "catalog_items", "items_base", "items_crackable", "items", "rooms", "room_models"]:
        statements.extend(re.findall(rf"CREATE TABLE `{table}` \(.*?;(?=\s*\n)", SOURCE, re.S))
        for insert in re.findall(rf"INSERT INTO `{table}` \(.*?;(?=\s*\n)", SOURCE, re.S):
            lines = insert.splitlines()
            header, values = lines[0], [line.strip().rstrip(',;') for line in lines[1:]]
            # MariaDB bootstrap has a 60 KB statement limit.
            for offset in range(0, len(values), 25):
                statements.append(header + '\n' + ',\n'.join(values[offset:offset + 25]) + ';')
    statements += ["ALTER TABLE catalog_items CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;",
                   "SET FOREIGN_KEY_CHECKS=1;",
                   "INSERT INTO rooms(id,owner_id,owner_name,name) VALUES (50,99,'RepairTestAdmin','Existing player room');",
                   "INSERT INTO items(user_id,room_id,item_id) VALUES (99,50,11313);",
                   "CREATE TABLE saved_prices AS SELECT id,cost_credits,cost_points,points_type,limited_stack,limited_sells FROM catalog_items;",
                   "CREATE TABLE assertions (label VARCHAR(100), passed INT NOT NULL CHECK(passed=1));"]
    return statements


def check(label, condition):
    return f"INSERT INTO assertions VALUES ('{label}', ({condition}));"


def run():
    statements = fixture("catalog_repair_complete") + REPAIRS
    statements += [
        check("no duplicate sibling orders", "SELECT COUNT(*)=0 FROM (SELECT 1 FROM catalog_pages GROUP BY parent_id,order_num HAVING COUNT(*)>1) d"),
        check("correct trimmed balloon offer", "SELECT COUNT(*)=1 FROM catalog_items WHERE page_id=757 AND item_ids='11308' AND cost_credits=3"),
        check("decorative balloon preserved", "SELECT COUNT(*)=1 FROM items_base WHERE id=11312 AND interaction_type='default'"),
        check("original reward pool preserved", "SELECT COUNT(*)=1 FROM items_crackable WHERE item_id=11308 AND prizes='11327:1;11314:2;11325:4;11315:4;11313:4;11330:6;11329:6;11320:6;11323:7;11318:7;11317:7;11328:9;11324:9;11321:9;11319:9;11322:10'"),
        check("all seven templates restored", "SELECT COUNT(*)=7 FROM catalog_bundle_repair_20261020 WHERE items_restored=1 AND room_id>0"),
        check("all 335 placements restored", "SELECT COUNT(*)=335 FROM items i JOIN catalog_bundle_repair_20261020 b ON b.room_id=i.room_id"),
        check("existing room preserved", "SELECT COUNT(*)=1 FROM rooms WHERE id=50 AND name='Existing player room'"),
        check("existing inventory preserved", "SELECT COUNT(*)=1 FROM items WHERE room_id=50 AND item_id=11313"),
        check("bundle offers linked", "SELECT COUNT(*)=7 FROM catalog_items c JOIN catalog_bundle_repair_20261020 b ON b.offer_id=c.id JOIN catalog_pages p ON p.id=b.page_id AND p.room_id=b.room_id JOIN items_base i ON i.id=CAST(c.item_ids AS UNSIGNED)"),
        check("prices stock and sales preserved", "SELECT COUNT(*)=0 FROM saved_prices s JOIN catalog_items c ON c.id=s.id WHERE s.cost_credits<>c.cost_credits OR s.cost_points<>c.cost_points OR s.points_type<>c.points_type OR s.limited_stack<>c.limited_stack OR s.limited_sells<>c.limited_sells"),
    ]
    statements += REPAIRS
    statements += [check("rerun creates no extra rooms", "SELECT COUNT(*)=8 FROM rooms"),
                   check("rerun creates no extra furniture", "SELECT COUNT(*)=336 FROM items"),
                   check("rerun creates no duplicate balloon", "SELECT COUNT(*)=1 FROM catalog_items WHERE page_id=757")]
    statements += fixture("catalog_repair_custom")
    statements += ["UPDATE catalog_pages SET room_id=50 WHERE id=1001;",
                   "UPDATE catalog_items SET item_ids='11313',cost_credits=123 WHERE id=11049;",
                   "INSERT INTO catalog_items(item_ids,page_id,catalog_name,cost_credits) VALUES ('11313',757,'Custom promotion',321);",
                   "DELETE FROM items_base WHERE item_name='darkelegant_c20_flowerpot';"]
    statements += REPAIRS
    statements += [check("staff promotion preserved", "SELECT COUNT(*)=1 FROM catalog_items WHERE page_id=757 AND catalog_name='Custom promotion' AND cost_credits=321"),
                   check("staff bundle preserved", "SELECT COUNT(*)=1 FROM catalog_items c JOIN catalog_pages p ON p.id=c.page_id WHERE c.id=11049 AND c.item_ids='11313' AND c.cost_credits=123 AND p.room_id=50"),
                   check("incomplete template not enabled", "SELECT COUNT(*)=0 FROM catalog_bundle_repair_20261020 WHERE page_id=1000")]
    payload = "\n".join(statements)
    binary, basedir, datadir = sys.argv[1:]
    completed = subprocess.run([binary, "--no-defaults", "--bootstrap", f"--basedir={basedir}", f"--datadir={datadir}", "--user=root"],
                               input=payload, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    Path("/tmp/catalog-repair-mariadb-test.log").write_text(completed.stdout + completed.stderr)
    if completed.returncode:
        print(completed.stderr[-4000:])
        raise SystemExit(completed.returncode)
    print("PASS: 16 MariaDB checks, 335 original placements, idempotency and staff-content preservation")


if __name__ == "__main__":
    run()
