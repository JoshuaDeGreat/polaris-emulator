-- Each player has separate navigator preferences for each category.
-- Preserve existing preferences while removing the legacy one-row-per-user limit.
ALTER TABLE `users_navigator_settings`
    DROP INDEX IF EXISTS `userid`,
    ADD UNIQUE KEY IF NOT EXISTS `user_category` (`user_id`, `caption`);
