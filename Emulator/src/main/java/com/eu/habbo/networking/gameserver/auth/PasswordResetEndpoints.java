package com.eu.habbo.networking.gameserver.auth;

import static com.eu.habbo.networking.gameserver.auth.AuthHttpUtil.errorPayload;
import static com.eu.habbo.networking.gameserver.auth.AuthHttpUtil.readString;
import static com.eu.habbo.networking.gameserver.auth.AuthHttpUtil.sendJson;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.gameclients.SessionResumeManager;
import com.eu.habbo.messages.outgoing.handshake.DisconnectReasonComposer;
import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Completes a one-time email recovery without exposing an account's existence. */
final class PasswordResetEndpoints {
    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordResetEndpoints.class);
    private static final String INVALID_LINK = "This recovery link is invalid or has expired. Please request another.";

    private PasswordResetEndpoints() {}

    static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static void handleReset(ChannelHandlerContext ctx, FullHttpRequest req, JsonObject body, String ip) {
        String token = readString(body, "token").trim();
        String password = readString(body, "newPassword");
        String confirm = readString(body, "confirmPassword");

        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, errorPayload(INVALID_LINK));
            return;
        }
        if (password.length() < 8 || password.length() > 256 || !password.equals(confirm)) {
            sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST,
                    errorPayload("Enter matching passwords of at least 8 and at most 256 characters."));
            return;
        }

        try (Connection conn = Emulator.getDatabase().getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            int userId = 0;
            try {
                try (PreparedStatement lookup = conn.prepareStatement(
                        "SELECT user_id FROM password_resets WHERE token = ? AND expires_at > NOW() LIMIT 1 FOR UPDATE")) {
                    lookup.setString(1, hashToken(token));
                    try (ResultSet rs = lookup.executeQuery()) {
                        if (rs.next()) userId = rs.getInt("user_id");
                    }
                }
                if (userId <= 0) {
                    conn.rollback();
                    sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, errorPayload(INVALID_LINK));
                    return;
                }

                try (PreparedStatement update = conn.prepareStatement(
                        "UPDATE users SET password = ?, auth_ticket = '', auth_ticket_expires_at = NULL, "
                                + "access_token_version = access_token_version + 1 WHERE id = ? LIMIT 1")) {
                    update.setString(1, PasswordHasher.hash(password, 12));
                    update.setInt(2, userId);
                    if (update.executeUpdate() != 1) {
                        conn.rollback();
                        sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, errorPayload(INVALID_LINK));
                        return;
                    }
                }
                RememberJwtService.revokeAllForUser(conn, userId);
                try (PreparedStatement delete = conn.prepareStatement("DELETE FROM password_resets WHERE user_id = ?")) {
                    delete.setInt(1, userId);
                    delete.executeUpdate();
                }
                conn.commit();
            } catch (Exception error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
            try {
                SessionResumeManager.getInstance().disposeGhostSession(userId);
                var active = Emulator.getGameServer().getGameClientManager().getHabbo(userId);
                if (active != null && active.getClient() != null) {
                    Emulator.getGameServer().getGameClientManager()
                            .disconnectWithReason(active.getClient(), DisconnectReasonComposer.LOGOUT);
                }
            } catch (Exception disconnectError) {
                LOGGER.error("[auth/reset-password] session eviction failed for user id={}", userId, disconnectError);
            }
            LOGGER.info("[auth/reset-password] credentials updated for user id={}", userId);
            JsonObject ok = new JsonObject();
            ok.addProperty("message", "Your password has been changed. You can now log in.");
            sendJson(ctx, req, HttpResponseStatus.OK, ok);
        } catch (Exception e) {
            LOGGER.error("[auth/reset-password] failed", e);
            sendJson(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, errorPayload("Server error."));
        }
    }
}
