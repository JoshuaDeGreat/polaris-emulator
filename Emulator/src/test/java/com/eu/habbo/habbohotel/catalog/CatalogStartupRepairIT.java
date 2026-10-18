package com.eu.habbo.habbohotel.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.eu.habbo.database.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import org.junit.jupiter.api.Test;

class CatalogStartupRepairIT {
    @Test
    void repairsOnlyNonLimitedCountersAndPreservesOriginalsAndCustomText() throws Exception {
        assumeTrue(TestDatabase.dockerAvailable(), "MariaDB integration test requires Docker");
        try (HikariDataSource source = TestDatabase.freshDatabase("catalog_startup_repair");
                Connection connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE catalog_items (id INT PRIMARY KEY, limited_stack INT, limited_sells INT)");
            statement.execute("INSERT INTO catalog_items VALUES (1,0,28),(2,100,4),(3,1,2),(4,0,0)");
            statement.execute("CREATE TABLE emulator_texts (`key` VARCHAR(100) PRIMARY KEY, `value` VARCHAR(255))");
            statement.execute("INSERT INTO emulator_texts VALUES ('commands.keys.cmd_roomfx','myfx')");

            applyRepair(connection);
            applyRepair(connection);

            try (var rows = statement.executeQuery("SELECT limited_sells FROM catalog_items ORDER BY id")) {
                for (int expected : new int[] {0,4,2,0}) {
                    rows.next();
                    assertEquals(expected, rows.getInt(1));
                }
            }
            try (var rows = statement.executeQuery("SELECT limited_sells FROM catalog_nonlimited_sales_backup_20261018")) {
                rows.next();
                assertEquals(28, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT `value` FROM emulator_texts WHERE `key`='commands.keys.cmd_roomfx'")) {
                rows.next();
                assertEquals("myfx", rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM emulator_texts")) {
                rows.next();
                assertEquals(2, rows.getInt(1));
            }
        }
    }

    private static void applyRepair(Connection connection) throws Exception {
        try (var input = CatalogStartupRepairIT.class.getResourceAsStream(
                "/db/migration/V20261018203000__repair_nonlimited_sales_and_roomfx_texts.sql");
                var statement = connection.createStatement()) {
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String sql : migration.split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }
}
