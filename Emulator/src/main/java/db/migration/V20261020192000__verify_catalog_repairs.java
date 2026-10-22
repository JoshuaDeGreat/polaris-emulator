package db.migration;

import com.eu.habbo.habbohotel.catalog.versioning.CatalogVersion;
import com.eu.habbo.habbohotel.catalog.versioning.CatalogVersionStatus;
import com.eu.habbo.habbohotel.catalog.versioning.JdbcCatalogLiveSnapshotRepository;
import com.eu.habbo.habbohotel.catalog.versioning.JdbcCatalogValidationDataRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.stream.Collectors;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs the actual post-repair result without exposing account data or adding an endpoint. */
public final class V20261020192000__verify_catalog_repairs extends BaseJavaMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(V20261020192000__verify_catalog_repairs.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        // This migration only reports diagnostics. A legacy catalogue with mixed
        // column collations must not prevent the emulator from starting.
        try {
            LOGGER.info(
                    "Catalogue repair verification: restored templates={}, restored furnishings={}, trimmed balloon offers={}",
                    count(connection, "SELECT COUNT(*) FROM catalog_bundle_repair_20261020 WHERE items_restored = 1"),
                    count(connection, "SELECT COUNT(*) FROM items i JOIN catalog_bundle_repair_20261020 b ON b.room_id = i.room_id WHERE b.items_restored = 1"),
                    count(connection, "SELECT COUNT(*) FROM catalog_items c JOIN items_base i ON c.item_ids = CAST(i.id AS CHAR) WHERE c.page_id = 757 AND i.item_name = 'habbo20_r20_crackable'"));
            CatalogVersion version = new CatalogVersion(
                    1, CatalogVersionStatus.PUBLISHED, null, 0, "Repair verification", 0, Instant.now(), null, null);
            var snapshot = new JdbcCatalogLiveSnapshotRepository().loadForRead(connection, version);
            var report = new JdbcCatalogValidationDataRepository().load(connection).validator().validate(snapshot);
            LOGGER.info(
                    "Catalogue repair verification: remaining issues={} {}",
                    report.issues().size(),
                    report.issues().stream().collect(Collectors.groupingBy(issue -> issue.code(), Collectors.counting())));
            report.issues().forEach(issue -> LOGGER.info(
                    "Catalogue repair remaining issue: {} {} #{} {}",
                    issue.code(), issue.entityType(), issue.entityId(), issue.message()));
        } catch (java.sql.SQLException diagnosticError) {
            LOGGER.warn("Catalogue verification diagnostics unavailable; startup may continue", diagnosticError);
        }

    }

    private static int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(sql)) {
            results.next();
            return results.getInt(1);
        }
    }
}
