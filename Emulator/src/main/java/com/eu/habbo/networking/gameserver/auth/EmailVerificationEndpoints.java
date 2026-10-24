package com.eu.habbo.networking.gameserver.auth;

import static com.eu.habbo.networking.gameserver.auth.AuthHttpUtil.EMAIL_RE;
import static com.eu.habbo.networking.gameserver.auth.AuthHttpUtil.errorPayload;
import static com.eu.habbo.networking.gameserver.auth.AuthHttpUtil.mintResetToken;
import static com.eu.habbo.networking.gameserver.auth.AuthHttpUtil.readString;
import static com.eu.habbo.networking.gameserver.auth.AuthHttpUtil.sendJson;

import com.eu.habbo.Emulator;
import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Email ownership proof. Never switch a player's address before proof of control. */
final class EmailVerificationEndpoints {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationEndpoints.class);

    private EmailVerificationEndpoints() {}

    static boolean request(int userId, String email) {
        if (userId <= 0 || email == null || !EMAIL_RE.matcher(email).matches()) return false;
        String token = mintResetToken();
        try (Connection conn = Emulator.getDatabase().getDataSource().getConnection()) {
            // Only one token per user; concurrent requests serialize through the unique key.
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement(
                        "SELECT requested_at, email FROM email_verification_tokens WHERE user_id = ? FOR UPDATE")) {
                    lock.setInt(1, userId);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (rs.next()
                                && rs.getTimestamp(1)
                                        .toInstant()
                                        .isAfter(Instant.now().minusSeconds(120))) {
                            conn.rollback();
                            return email.equalsIgnoreCase(rs.getString("email")); // Same-address cooldown only.
                        }
                    }
                }
                try (PreparedStatement upsert = conn.prepareStatement(
                        "INSERT INTO email_verification_tokens (user_id, email, token_hash, expires_at, requested_at) "
                                + "VALUES (?, ?, ?, ?, NOW()) ON DUPLICATE KEY UPDATE email = VALUES(email), "
                                + "token_hash = VALUES(token_hash), expires_at = VALUES(expires_at), requested_at = NOW()")) {
                    upsert.setInt(1, userId);
                    upsert.setString(2, email);
                    upsert.setString(3, PasswordResetEndpoints.hashToken(token));
                    upsert.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(1800)));
                    upsert.executeUpdate();
                }
                conn.commit();
            } catch (Exception error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(oldAutoCommit);
            }
            String url = Emulator.getConfig().getValue("email.verify.url", "https://heavenhotel.uk/client/?verify=1")
                    + "#token=" + token;
            String text = "Welcome to Heaven Hotel!\n\nConfirm that you control this email address "
                    + "by opening this link within 30 minutes:\n\n" + url
                    + "\n\nIf you did not request this, ignore the message.";
            return SessionEndpoints.submitResetEmail(() -> {
                if (!SmtpMailService.send(email, "Verify your Heaven Hotel email", text, AccountEmailTemplate.emailVerification(url))) {
                    LOGGER.warn("[auth/email-verification] delivery failed for user id={}", userId);
                }
            });
        } catch (Exception e) {
            LOGGER.error("[auth/email-verification] could not issue verification for user id={}", userId, e);
            return false;
        }
    }

    static void handleRequest(ChannelHandlerContext ctx, FullHttpRequest req, String ip) {
        String authorization = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        String bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim()
                : "";
        int userId = AccessTokenService.verify(bearer);
        if (userId <= 0) {
            sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, errorPayload("Log in to verify your email."));
            return;
        }
        if (!AuthRateLimiter.tryProbe(ip)) {
            sendJson(ctx, req, HttpResponseStatus.TOO_MANY_REQUESTS, errorPayload("Please wait before trying again."));
            return;
        }
        try (Connection conn = Emulator.getDatabase().getDataSource().getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT mail, email_verified_at FROM users WHERE id = ? LIMIT 1")) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getTimestamp("email_verified_at") == null) {
                    request(userId, rs.getString("mail"));
                }
            }
        } catch (Exception e) {
            LOGGER.error("[auth/email-verification] request failed for user id={}", userId, e);
        }
        JsonObject response = new JsonObject();
        response.addProperty("message", "If verification is needed, an email will arrive shortly.");
        sendJson(ctx, req, HttpResponseStatus.OK, response);
    }

    static void handleConfirm(ChannelHandlerContext ctx, FullHttpRequest req, JsonObject body, String ip) {
        if (!AuthRateLimiter.tryProbe(ip)) {
            sendJson(ctx, req, HttpResponseStatus.TOO_MANY_REQUESTS, errorPayload("Please wait before trying again."));
            return;
        }
        String token = readString(body, "token");
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            sendJson(ctx, req, HttpResponseStatus.BAD_REQUEST, errorPayload("Invalid or expired verification link."));
            return;
        }
        try (Connection conn = Emulator.getDatabase().getDataSource().getConnection()) {
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            String email = null;
            int userId = 0;
            try {
                try (PreparedStatement lookup =
                        conn.prepareStatement("SELECT user_id, email FROM email_verification_tokens "
                                + "WHERE token_hash = ? AND expires_at > NOW() FOR UPDATE")) {
                    lookup.setString(1, PasswordResetEndpoints.hashToken(token));
                    try (ResultSet rs = lookup.executeQuery()) {
                        if (rs.next()) {
                            userId = rs.getInt(1);
                            email = rs.getString(2);
                        }
                    }
                }
                if (userId == 0) {
                    conn.rollback();
                    sendJson(
                            ctx,
                            req,
                            HttpResponseStatus.BAD_REQUEST,
                            errorPayload("Invalid or expired verification link."));
                    return;
                }
                try (PreparedStatement update =
                        conn.prepareStatement("UPDATE users SET mail = ?, email_verified_at = NOW(), "
                                + "auth_ticket = '', auth_ticket_expires_at = NULL, "
                                + "access_token_version = access_token_version + 1 WHERE id = ?")) {
                    update.setString(1, email);
                    update.setInt(2, userId);
                    if (update.executeUpdate() != 1) throw new SQLException("Missing verification account");
                }
                RememberJwtService.revokeAllForUser(conn, userId);
                try (PreparedStatement delete =
                        conn.prepareStatement("DELETE FROM email_verification_tokens WHERE user_id = ?")) {
                    delete.setInt(1, userId);
                    delete.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(oldAutoCommit);
            }
            PasswordResetEndpoints.evictGameSessions(userId);
            AvailabilityCache.invalidateEmail(email);
            JsonObject ok = new JsonObject();
            ok.addProperty("message", "Your email address is verified. Please log in again.");
            sendJson(ctx, req, HttpResponseStatus.OK, ok);
        } catch (Exception e) {
            // Unique mail constraint can reject an address claimed since the request was issued.
            LOGGER.error("[auth/email-verification] confirmation failed", e);
            sendJson(
                    ctx,
                    req,
                    HttpResponseStatus.BAD_REQUEST,
                    errorPayload("Verification could not be completed. Request another link."));
        }
    }
}
