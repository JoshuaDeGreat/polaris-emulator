package com.eu.habbo.networking.gameserver.auth;

import com.eu.habbo.Emulator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;
import java.util.HexFormat;

/** Website-only opaque sessions: digests at rest, individual logout, credential-bound revocation. */
final class WebsiteSessionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    record Issued(String token, long expiresAt) {}
    private WebsiteSessionService() {}

    static Issued issue(Connection conn, int userId, String password) throws SQLException {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String token = "hws_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expires = Emulator.getIntUnixTimestamp() + 1800L;
        try (PreparedStatement prune = conn.prepareStatement("DELETE FROM website_sessions WHERE expires_at<=CURRENT_TIMESTAMP")) { prune.executeUpdate(); }
        try (PreparedStatement insert = conn.prepareStatement("INSERT INTO website_sessions(token_hash,user_id,security_version,credential_hash,expires_at) SELECT ?,id,access_token_version,?,? FROM users WHERE id=? AND BINARY password=BINARY ?")) {
            insert.setString(1,digest(token));insert.setString(2,digest(password));insert.setTimestamp(3,Timestamp.from(java.time.Instant.ofEpochSecond(expires)));insert.setInt(4,userId);insert.setString(5,password);
            if(insert.executeUpdate()!=1)throw new SQLException("Website session could not be created");
        }
        return new Issued(token,expires);
    }

    static int authenticate(String token) {
        if(token!=null&&token.startsWith("hws_"))return verify(token);
        return AccessTokenService.verify(token);
    }
    static int verify(String token) {
        if(!validFormat(token))return 0;
        try(Connection conn=Emulator.getDatabase().getDataSource().getConnection();PreparedStatement select=conn.prepareStatement("SELECT s.user_id,s.credential_hash,u.password FROM website_sessions s JOIN users u ON u.id=s.user_id AND u.access_token_version=s.security_version WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP LIMIT 1")) {
            select.setString(1,digest(token));try(ResultSet row=select.executeQuery()){
                if(!row.next()||!MessageDigest.isEqual(row.getString("credential_hash").getBytes(StandardCharsets.US_ASCII),digest(row.getString("password")).getBytes(StandardCharsets.US_ASCII)))return 0;
                return row.getInt("user_id");
            }
        }catch(SQLException e){org.slf4j.LoggerFactory.getLogger(WebsiteSessionService.class).warn("Website session lookup failed");return 0;}
    }
    static void revoke(String token)throws SQLException {
        if(!validFormat(token))return;
        try(Connection conn=Emulator.getDatabase().getDataSource().getConnection();PreparedStatement stmt=conn.prepareStatement("DELETE FROM website_sessions WHERE token_hash=?")){stmt.setString(1,digest(token));stmt.executeUpdate();}
    }
    static boolean validFormat(String token){return token!=null&&token.matches("hws_[A-Za-z0-9_-]{43}");}
    static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((text==null?"":text).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
