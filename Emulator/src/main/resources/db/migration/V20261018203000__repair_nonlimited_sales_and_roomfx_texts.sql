-- Preserve imported counters before clearing stale LTD metadata on non-limited offers.
-- Real limited editions (limited_stack > 0), issued furniture and prices are untouched.
CREATE TABLE IF NOT EXISTS catalog_nonlimited_sales_backup_20261018 (
    catalog_item_id INT NOT NULL PRIMARY KEY,
    limited_stack INT NOT NULL,
    limited_sells INT NOT NULL,
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT IGNORE INTO catalog_nonlimited_sales_backup_20261018
    (catalog_item_id, limited_stack, limited_sells)
SELECT id, limited_stack, limited_sells FROM catalog_items
WHERE limited_stack = 0 AND limited_sells > 0;

UPDATE catalog_items AS item
JOIN catalog_nonlimited_sales_backup_20261018 AS saved ON saved.catalog_item_id = item.id
SET item.limited_sells = 0
WHERE item.limited_stack = 0 AND item.limited_sells = saved.limited_sells;

INSERT IGNORE INTO emulator_texts (`key`, `value`) VALUES
    ('commands.keys.cmd_roomfx', 'roomfx'),
    ('commands.error.cmd_roomfx.unknown', 'Effects: rotate, shake, zoom, disco.');
