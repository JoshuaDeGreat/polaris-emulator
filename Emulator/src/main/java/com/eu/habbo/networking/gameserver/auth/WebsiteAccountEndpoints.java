package com.eu.habbo.networking.gameserver.auth;

import com.eu.habbo.Emulator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import java.sql.*;

final class WebsiteAccountEndpoints {
    static final String PROFILE = "/api/community/account";
    static boolean isRoute(String path) { return path.equals(PROFILE) || path.equals(PROFILE+"/logout") || path.equals(PROFILE+"/verify-email"); }
    private WebsiteAccountEndpoints() {}

    static void handle(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        if (!path.equals(PROFILE)) { handleAction(ctx,req,path); return; }
        if (req.method() != HttpMethod.GET) {
            AuthHttpUtil.sendJson(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, AuthHttpUtil.errorPayload("Use GET.")); return;
        }
        String auth = req.headers().get(HttpHeaderNames.AUTHORIZATION, "");
        int userId = auth.startsWith("Bearer ") ? WebsiteSessionService.authenticate(auth.substring(7)) : 0;
        if (userId <= 0) { denied(ctx, req); return; }
        try (Connection conn = Emulator.getDatabase().getDataSource().getConnection()) {
            if (AuthHttpUtil.lookupAccountBan(conn, userId) != null) { denied(ctx, req); return; }
            JsonObject profile = new JsonObject();
            // The authenticated subject is the only account selector. Never accept a user-id parameter.
            try (PreparedStatement stmt = conn.prepareStatement("SELECT id,username,motto,look,gender,account_created,last_login,mail,email_verified_at,credits FROM users WHERE id=? LIMIT 1")) {
                stmt.setInt(1, userId);
                try (ResultSet row = stmt.executeQuery()) {
                    if (!row.next()) { denied(ctx, req); return; }
                    profile.addProperty("id", userId);
                    for (String name : new String[]{"username","motto","look","gender"}) profile.addProperty(name, row.getString(name));
                    profile.addProperty("createdAt", row.getLong("account_created"));
                    profile.addProperty("lastLogin", row.getLong("last_login"));
                    profile.addProperty("email", row.getString("mail"));
                    profile.addProperty("emailVerified", row.getTimestamp("email_verified_at") != null);
                    profile.addProperty("credits", row.getInt("credits"));
                }
            }
            int duckets = 0, diamonds = 0;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT type,amount FROM users_currency WHERE user_id=? AND type IN (0,5)")) {
                stmt.setInt(1, userId);
                try (ResultSet row = stmt.executeQuery()) { while (row.next()) {
                    if (row.getInt("type") == 0) duckets = Math.max(0, row.getInt("amount"));
                    else diamonds = Math.max(0, row.getInt("amount"));
                }}
            }
            var online = Emulator.getGameEnvironment().getHabboManager().getHabbo(userId);
            if (online != null && online.getHabboInfo() != null) {
                profile.addProperty("credits", online.getHabboInfo().getCredits());
                duckets = online.getHabboInfo().getCurrencyAmount(0); diamonds = online.getHabboInfo().getCurrencyAmount(5);
            }
            profile.addProperty("duckets", duckets); profile.addProperty("diamonds", diamonds);
            profile.addProperty("online", online != null && online.getClient() != null);
            JsonArray rooms = new JsonArray();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT id,name,state FROM rooms WHERE owner_id=? ORDER BY id DESC LIMIT 50")) {
                stmt.setInt(1, userId);
                try (ResultSet row = stmt.executeQuery()) { while(row.next()) {
                    JsonObject room = new JsonObject();room.addProperty("id",row.getInt("id"));room.addProperty("name",row.getString("name"));room.addProperty("state",row.getString("state"));rooms.add(room);
                }}
            }
            profile.add("rooms", rooms);
            profile.addProperty("canAccessStaff", WebsiteStaffAccess.canAccessStaff(userId));
            profile.addProperty("canEditNews", WebsiteStaffAccess.find(userId) != null);
            AuthHttpUtil.sendJson(ctx, req, HttpResponseStatus.OK, profile);
        } catch (SQLException e) {
            org.slf4j.LoggerFactory.getLogger(WebsiteAccountEndpoints.class).warn("Website profile unavailable", e);
            AuthHttpUtil.sendJson(ctx, req, HttpResponseStatus.SERVICE_UNAVAILABLE, AuthHttpUtil.errorPayload("Your account is temporarily unavailable. Please try again."));
        }
    }
    private static void handleAction(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        if(req.method()!=HttpMethod.POST || !CommunityEndpoints.sameOrigin(req.headers().get(HttpHeaderNames.ORIGIN),req.headers().get(HttpHeaderNames.HOST))){AuthHttpUtil.sendJson(ctx,req,HttpResponseStatus.FORBIDDEN,AuthHttpUtil.errorPayload("Open your account on the website."));return;}
        String auth=req.headers().get(HttpHeaderNames.AUTHORIZATION,"");String token=auth.startsWith("Bearer ")?auth.substring(7):"";
        int userId=WebsiteSessionService.authenticate(token);if(userId<=0){denied(ctx,req);return;}
        try {
            if(path.endsWith("/logout")){WebsiteSessionService.revoke(token);JsonObject ok=new JsonObject();ok.addProperty("ok",true);AuthHttpUtil.sendJson(ctx,req,HttpResponseStatus.OK,ok);return;}
            try(Connection conn=Emulator.getDatabase().getDataSource().getConnection();PreparedStatement stmt=conn.prepareStatement("SELECT mail,email_verified_at FROM users WHERE id=? LIMIT 1")){
                stmt.setInt(1,userId);try(ResultSet row=stmt.executeQuery()){
                    if(!row.next()||AuthHttpUtil.lookupAccountBan(conn,userId)!=null){denied(ctx,req);return;}
                    if(row.getTimestamp("email_verified_at")==null&&!EmailVerificationEndpoints.request(userId,row.getString("mail"))){AuthHttpUtil.sendJson(ctx,req,HttpResponseStatus.TOO_MANY_REQUESTS,AuthHttpUtil.errorPayload("Please wait before requesting another verification email."));return;}
                }
            }
            JsonObject ok=new JsonObject();ok.addProperty("message","If verification is needed, a link has been sent to your account email.");AuthHttpUtil.sendJson(ctx,req,HttpResponseStatus.OK,ok);
        }catch(SQLException e){AuthHttpUtil.sendJson(ctx,req,HttpResponseStatus.SERVICE_UNAVAILABLE,AuthHttpUtil.errorPayload("Please try again shortly."));}
    }
    private static void denied(ChannelHandlerContext ctx, FullHttpRequest req) {
        AuthHttpUtil.sendJson(ctx, req, HttpResponseStatus.UNAUTHORIZED, AuthHttpUtil.errorPayload("Please sign in again."));
    }
}
