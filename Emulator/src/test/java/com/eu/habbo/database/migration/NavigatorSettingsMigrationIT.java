package com.eu.habbo.database.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.eu.habbo.database.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class NavigatorSettingsMigrationIT {
    @Test
    void preservesPreferencesAndAllowsIndependentCategoriesPerPlayer() throws Exception {
        assumeTrue(TestDatabase.dockerAvailable(), "Docker is required for the MariaDB migration test");
        try (HikariDataSource dataSource = TestDatabase.freshDatabase("navigator_settings");
                var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users_navigator_settings (
                        user_id INT NOT NULL,
                        caption VARCHAR(128) NOT NULL,
                        list_type ENUM('list','thumbnails') NOT NULL DEFAULT 'list',
                        display ENUM('visible','collapsed') NOT NULL DEFAULT 'visible',
                        UNIQUE KEY userid (user_id)
                    ) ENGINE=MyISAM
                    """);
            statement.execute("INSERT INTO users_navigator_settings VALUES (2, 'official-root', 'thumbnails', 'collapsed')");
            statement.execute(Files.readString(Path.of(
                    "src/main/resources/db/migration/V20261018120000__navigator_category_settings_key.sql")));
            statement.execute("INSERT INTO users_navigator_settings VALUES (2, 'popular', 'list', 'visible')");
            statement.execute("INSERT INTO users_navigator_settings VALUES (3, 'official-root', 'list', 'visible')");
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO users_navigator_settings VALUES (2, 'official-root', 'list', 'visible')"));
            try (var result = statement.executeQuery(
                    "SELECT list_type, display FROM users_navigator_settings WHERE user_id=2 AND caption='official-root'")) {
                result.next();
                assertEquals("thumbnails", result.getString(1));
                assertEquals("collapsed", result.getString(2));
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM users_navigator_settings")) {
                result.next();
                assertEquals(3, result.getInt(1));
            }
        }
    }
}
