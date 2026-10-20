-- Keep the previous order values so an operator can restore them. Only parents
-- containing a collision are renumbered; their existing (order_num, id) order is
-- retained. Names, visibility, prices, limited stock and sales are untouched.
CREATE TABLE IF NOT EXISTS catalog_page_order_repair_20261020 (
    catalog_type VARCHAR(16) NOT NULL,
    page_id INT NOT NULL,
    parent_id INT NOT NULL,
    old_order INT NOT NULL,
    new_order INT NOT NULL,
    PRIMARY KEY (catalog_type, page_id)
);

INSERT IGNORE INTO catalog_page_order_repair_20261020
    (catalog_type, page_id, parent_id, old_order, new_order)
SELECT 'NORMAL', p.id, p.parent_id, p.order_num,
       ROW_NUMBER() OVER (PARTITION BY p.parent_id ORDER BY p.order_num, p.id) - 1
FROM catalog_pages p
JOIN (
    SELECT DISTINCT parent_id FROM catalog_pages
    GROUP BY parent_id, order_num HAVING COUNT(*) > 1
) collisions ON collisions.parent_id = p.parent_id;

INSERT IGNORE INTO catalog_page_order_repair_20261020
    (catalog_type, page_id, parent_id, old_order, new_order)
SELECT 'BUILDER', p.id, p.parent_id, p.order_num,
       ROW_NUMBER() OVER (PARTITION BY p.parent_id ORDER BY p.order_num, p.id) - 1
FROM catalog_pages_bc p
JOIN (
    SELECT DISTINCT parent_id FROM catalog_pages_bc
    GROUP BY parent_id, order_num HAVING COUNT(*) > 1
) collisions ON collisions.parent_id = p.parent_id;

UPDATE catalog_pages p
JOIN catalog_page_order_repair_20261020 b ON b.catalog_type = 'NORMAL' AND b.page_id = p.id
SET p.order_num = b.new_order
WHERE p.parent_id = b.parent_id AND p.order_num = b.old_order;

UPDATE catalog_pages_bc p
JOIN catalog_page_order_repair_20261020 b ON b.catalog_type = 'BUILDER' AND b.page_id = p.id
SET p.order_num = b.new_order
WHERE p.parent_id = b.parent_id AND p.order_num = b.old_order;

-- The trimmed balloon is the existing r20 crackable, NOT the similarly named
-- c20 decorative balloon. Check its existing rewards against the advertised
-- pool before making the stock promotion purchasable. Do not rewrite rewards.
SET @balloon_id = (SELECT MIN(id) FROM items_base WHERE item_name = 'habbo20_r20_crackable');
SET @balloon_prizes = (
    SELECT GROUP_CONCAT(CONCAT(i.id, ':', rewards.chance) ORDER BY rewards.position SEPARATOR ';')
    FROM (
        SELECT 1 position, 'rare_blackrosegold_dragonlamp' name, 1 chance
        UNION ALL SELECT 2, 'rare_blackrosegold_icecream', 2
        UNION ALL SELECT 3, 'rare_blackrosegold_elephant_statue', 4
        UNION ALL SELECT 4, 'rare_blackrosegold_fountain', 4
        UNION ALL SELECT 5, 'rare_blackrosegold_parasol', 4
        UNION ALL SELECT 6, 'rare_blackrosegold_fan', 6
        UNION ALL SELECT 7, 'rare_blackrosegold_scifiport', 6
        UNION ALL SELECT 8, 'rare_blackrosegold_pillow', 6
        UNION ALL SELECT 9, 'rare_blackrosegold_scifirocket', 7
        UNION ALL SELECT 10, 'rare_blackrosegold_scifidoor', 7
        UNION ALL SELECT 11, 'rare_blackrosegold_beehive_bulb', 7
        UNION ALL SELECT 12, 'rare_blackrosegold_sleepingbag', 9
        UNION ALL SELECT 13, 'rare_blackrosegold_marquee', 9
        UNION ALL SELECT 14, 'rare_blackrosegold_wooden_screen', 9
        UNION ALL SELECT 15, 'rare_blackrosegold_pillar', 9
        UNION ALL SELECT 16, 'rare_blackrosegold_barrier', 10
    ) rewards
    JOIN (SELECT item_name, MIN(id) id FROM items_base GROUP BY item_name) i ON i.item_name = rewards.name
    HAVING COUNT(*) = 16
);

-- Use the same price as the hotel's existing rose gold balloon offer. Keep that
-- offer and all existing sales intact; never replace an operator's page contents.
INSERT INTO catalog_items
    (item_ids, page_id, catalog_name, cost_credits, cost_points, points_type,
     amount, limited_stack, limited_sells, order_number, offer_id, song_id,
     extradata, have_offer, club_only)
SELECT CAST(i.id AS CHAR), p.id, i.item_name, source.cost_credits,
       source.cost_points, source.points_type, 1, 0, 0, 1, -1, 0,
       '0', '0', source.club_only
FROM catalog_pages p
JOIN catalog_items source ON source.id = (
    SELECT MIN(id) FROM catalog_items
    WHERE catalog_name = 'habbo20_c20_rosegoldballoon' AND limited_stack = 0
)
JOIN items_base i ON i.id = @balloon_id AND i.interaction_type = 'crackable'
JOIN items_crackable c ON c.item_id = i.id AND c.prizes = @balloon_prizes AND c.`count` = 2
WHERE p.id = 757 AND p.caption_save = 'trimmed_rosegold20'
  AND NOT EXISTS (SELECT 1 FROM catalog_items existing WHERE existing.page_id = p.id);

INSERT INTO catalog_id_sequences (entity_type, catalog_type, next_id)
SELECT 'OFFER', 'NORMAL', COALESCE(MAX(id), 0) + 1 FROM catalog_items
ON DUPLICATE KEY UPDATE next_id = GREATEST(next_id, VALUES(next_id));
