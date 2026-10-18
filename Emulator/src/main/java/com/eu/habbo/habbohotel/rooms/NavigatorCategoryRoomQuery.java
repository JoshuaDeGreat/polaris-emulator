package com.eu.habbo.habbohotel.rooms;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Saved category listings must not depend on a room being present in the active-room cache. */
final class NavigatorCategoryRoomQuery {
    static final String SQL = """
            SELECT id FROM rooms
            WHERE category = ? AND is_public = '0' AND (? = 1 OR state <> 'invisible')
            ORDER BY users DESC, id DESC LIMIT ?
            """;

    private NavigatorCategoryRoomQuery() {}

    static List<Integer> findIds(Connection connection, int category, int limit, boolean showInvisible)
            throws SQLException {
        if (limit <= 0) return List.of();
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SQL)) {
            statement.setInt(1, category);
            statement.setBoolean(2, showInvisible);
            statement.setInt(3, Math.min(limit, 100));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) ids.add(rows.getInt("id"));
            }
        }
        return ids;
    }
}
