package com.eu.habbo.networking.gameserver.auth;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.permissions.Permission;
import com.eu.habbo.habbohotel.permissions.Rank;
import java.sql.*;

/** Website-only news authorization. Does not change in-game Housekeeping access. */
final class WebsiteStaffAccess {
    record Staff(int id, String username) {}
    private WebsiteStaffAccess() {}

    static Staff find(int userId) throws SQLException {
        if (userId <= 0) return null;
        try (Connection conn = Emulator.getDatabase().getDataSource().getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT username, `rank` FROM users WHERE id=? LIMIT 1")) {
            stmt.setInt(1, userId);
            try (ResultSet row = stmt.executeQuery()) {
                if (!row.next() || AuthHttpUtil.lookupAccountBan(conn, userId) != null) return null;
                Rank rank = Emulator.getGameEnvironment().getPermissionsManager().getRank(row.getInt("rank"));
                if (rank == null || !allowed(conn, userId, rank, Permission.ACC_HOUSEKEEPING)
                        || !allowed(conn, userId, rank, "acc_hk_hotel")) return null;
                boolean locked = false;
                try (PreparedStatement lock = conn.prepareStatement("SELECT `value` FROM emulator_settings WHERE `key`='housekeeping.lockdown' LIMIT 1");
                        ResultSet rows = lock.executeQuery()) { if (rows.next()) locked = "1".equals(rows.getString(1)); }
                int highest = Emulator.getGameEnvironment().getPermissionsManager().getAllRanks().stream()
                        .mapToInt(Rank::getId).max().orElse(Integer.MAX_VALUE);
                if (locked && rank.getId() < highest) return null;
                return new Staff(userId, row.getString("username"));
            }
        }
    }

    private static boolean allowed(Connection conn, int userId, Rank rank, String permission) throws SQLException {
        // Read active overrides on every privileged request; SQL failure denies access.
        try (PreparedStatement stmt = conn.prepareStatement("SELECT value FROM user_permission_overrides WHERE user_id=? AND permission_key=? AND (expires_at=0 OR expires_at>?) LIMIT 1")) {
            stmt.setInt(1, userId); stmt.setString(2, permission); stmt.setInt(3, Emulator.getIntUnixTimestamp());
            try (ResultSet row = stmt.executeQuery()) {
                if (row.next()) return permissionDecision(rank.hasPermission(permission, false), row.getInt(1));
            }
        }
        return permissionDecision(rank.hasPermission(permission, false), null);
    }

    static boolean permissionDecision(boolean rankAllowed, Integer override) {
        return override == null ? rankAllowed : override == 1;
    }
}
