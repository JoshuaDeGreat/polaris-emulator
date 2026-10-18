-- Add a normal, trade-enabled room category without renaming existing categories.
INSERT INTO `navigator_flatcats`
    (`min_rank`, `caption_save`, `caption`, `can_trade`, `max_user_count`, `public`, `list_type`, `order_num`)
SELECT 1, 'caption_save_trading', '${navigator.flatcategory.global.TRADING}', '1', 100, '0', 0, 10
WHERE NOT EXISTS (
    SELECT 1 FROM `navigator_flatcats`
    WHERE `caption_save` IN ('caption_save_trading', 'trading', 'trade')
       OR `caption` IN ('${navigator.flatcategory.global.TRADING}', '${navigator.flatcategory.global.TRADE}', 'Trading', 'Trade')
);
