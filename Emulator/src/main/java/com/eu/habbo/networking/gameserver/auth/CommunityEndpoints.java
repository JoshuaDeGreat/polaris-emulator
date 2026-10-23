package com.eu.habbo.networking.gameserver.auth;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.MaintenanceMode;
import com.eu.habbo.habbohotel.modtool.HousekeepingAuditLog;
import com.eu.habbo.habbohotel.navigation.PublicRoomGallery;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.messages.incoming.housekeeping.HousekeepingGalleryAccess;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/** Public community feeds deliberately exclude operational stats and account data.
 * Writes reuse existing access tokens, online staff permissions and lockdown gates. */
final class CommunityEndpoints {
    static final String BASE = "/api/community/";
    private static final String STAFF = BASE + "staff/news";
    private static volatile JsonObject cachedStatus;
    private static volatile long cachedStatusAt;

    private CommunityEndpoints() {}

    static boolean isRoute(String path) {
        return path.equals(BASE + "status") || path.equals(BASE + "news")
                || path.equals(BASE + "rooms") || path.equals(STAFF)
                || path.equals("/community/staff");
    }

    static void handle(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        boolean publicFeed = path.equals(BASE + "status") || path.equals(BASE + "news") || path.equals(BASE + "rooms");
        try {
            if (req.method() == HttpMethod.OPTIONS) {
                send(ctx, req, HttpResponseStatus.NO_CONTENT, "", "application/json", publicFeed);
                return;
            }
            if (publicFeed) {
                if (req.method() != HttpMethod.GET && req.method() != HttpMethod.HEAD) {
                    json(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("Use GET."), true);
                    return;
                }
                JsonObject result;
                if (path.equals(BASE + "status")) result = status();
                else if (path.equals(BASE + "news")) result = news(false);
                else result = PublicRoomGallery.list(false);
                json(ctx, req, HttpResponseStatus.OK, result, true);
                return;
            }
            if (path.equals("/community/staff")) {
                if (req.method() != HttpMethod.GET && req.method() != HttpMethod.HEAD) {
                    json(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("Use GET."), false);
                    return;
                }
                try (var stream = CommunityEndpoints.class.getResourceAsStream("/community-staff.html")) {
                    if (stream == null) throw new IllegalStateException("Staff page missing");
                    send(ctx, req, HttpResponseStatus.OK, new String(stream.readAllBytes(), StandardCharsets.UTF_8), "text/html; charset=utf-8", false);
                }
                return;
            }
            // Browser editor is hosted by Polaris itself; no cross-origin administrative access.
            if (req.method() != HttpMethod.GET && !sameOrigin(req.headers().get(HttpHeaderNames.ORIGIN), req.headers().get(HttpHeaderNames.HOST))) {
                json(ctx, req, HttpResponseStatus.FORBIDDEN, error("Open the editor on the hotel server."), false);
                return;
            }
            String auth = req.headers().get(HttpHeaderNames.AUTHORIZATION, "");
            int userId = auth.startsWith("Bearer ") ? WebsiteSessionService.authenticate(auth.substring(7)) : 0;
            WebsiteStaffAccess.Staff operator = WebsiteStaffAccess.find(userId);
            if (operator == null) {
                json(ctx, req, HttpResponseStatus.FORBIDDEN, error("Your account does not have access to the news desk."), false);
                return;
            }
            if (req.method() == HttpMethod.GET) {
                json(ctx, req, HttpResponseStatus.OK, news(true), false);
                return;
            }
            if (req.method() != HttpMethod.POST) {
                json(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, error("Use GET or POST."), false);
                return;
            }
            if (req.content().readableBytes() > 50000) {
                json(ctx, req, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, error("Article is too large."), false);
                return;
            }
            JsonObject body = JsonParser.parseString(req.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
            validateArticle(body);
            int id = AuthHttpUtil.readInt(body, "id", 0);
            try (Connection conn = Emulator.getDatabase().getDataSource().getConnection()) {
                String sql = id == 0
                        ? "INSERT INTO ui_news (title,body,image,link_text,link_url,enabled,sort_order) VALUES (?,?,?,?,?,?,?)"
                        : "UPDATE ui_news SET title=?,body=?,image=?,link_text=?,link_url=?,enabled=?,sort_order=? WHERE id=?";
                try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setString(1, AuthHttpUtil.readString(body,"title").trim());
                    stmt.setString(2, AuthHttpUtil.readString(body,"body").trim());
                    stmt.setString(3, AuthHttpUtil.readString(body,"image").trim());
                    stmt.setString(4, AuthHttpUtil.readString(body,"linkText").trim());
                    stmt.setString(5, AuthHttpUtil.readString(body,"linkUrl").trim());
                    stmt.setInt(6, body.get("enabled").getAsBoolean() ? 1 : 0);
                    stmt.setInt(7, body.get("sortOrder").getAsInt());
                    if (id > 0) stmt.setInt(8,id);
                    if (stmt.executeUpdate() == 0) {
                        json(ctx, req, HttpResponseStatus.NOT_FOUND, error("Article not found or unchanged."), false);
                        return;
                    }
                    if (id == 0) try (ResultSet keys = stmt.getGeneratedKeys()) { if (keys.next()) id = keys.getInt(1); }
                }
            }
            StaticContentEndpoints.invalidateNews();
            HousekeepingAuditLog.log(operator.id(), operator.username(),
                    "hotel.news.save", "news", id, AuthHttpUtil.readString(body,"title"),
                    "enabled=" + body.get("enabled").getAsBoolean(), AuthHttpUtil.resolveClientIp(ctx, req));
            json(ctx, req, HttpResponseStatus.OK, news(true), false);
        } catch (IllegalArgumentException ex) {
            json(ctx, req, HttpResponseStatus.BAD_REQUEST, error("Check the article fields. Use plain text and HTTPS image/link URLs."), publicFeed);
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(CommunityEndpoints.class).error("Community endpoint failed: {}", path, ex);
            json(ctx, req, HttpResponseStatus.SERVICE_UNAVAILABLE, error("Hotel content is temporarily unavailable."), publicFeed);
        }
    }

    static boolean sameOrigin(String origin, String host) {
        if (origin == null || host == null) return false;
        try { URI u = URI.create(origin); return "https".equals(u.getScheme()) && host.equalsIgnoreCase(u.getRawAuthority()); }
        catch (IllegalArgumentException ex) { return false; }
    }

    static void validateArticle(JsonObject body) {
        String title = AuthHttpUtil.readString(body,"title").trim();
        String text = AuthHttpUtil.readString(body,"body").trim();
        int id = AuthHttpUtil.readInt(body,"id",0);
        int order = AuthHttpUtil.readInt(body,"sortOrder",-1);
        if (title.isEmpty() || title.length()>150 || text.isEmpty() || text.length()>20000 || id<0 || order<0 || order>100000
                || !body.has("enabled") || !body.get("enabled").isJsonPrimitive() || !body.get("enabled").getAsJsonPrimitive().isBoolean()
                || AuthHttpUtil.readString(body,"linkText").length()>80) throw new IllegalArgumentException();
        for (String key : new String[]{"image","linkUrl"}) {
            String value = AuthHttpUtil.readString(body,key).trim();
            if (value.isEmpty()) continue;
            URI u = URI.create(value);
            if (value.length()>255 || !"https".equals(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null) throw new IllegalArgumentException();
        }
    }

    private static synchronized JsonObject status() throws SQLException {
        long now = System.currentTimeMillis();
        if (cachedStatus != null && now-cachedStatusAt < 10000) return cachedStatus.deepCopy();
        JsonObject result = new JsonObject();
        result.addProperty("online", Emulator.getGameEnvironment().getHabboManager().getOnlineCount());
        try (Connection conn = Emulator.getDatabase().getDataSource().getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM rooms WHERE state='open'");
                ResultSet rows = stmt.executeQuery()) {
            if (rows.next()) result.addProperty("rooms",rows.getLong(1));
        }
        result.addProperty("maintenance", MaintenanceMode.isEnabled());
        cachedStatus = result; cachedStatusAt = now;
        return result.deepCopy();
    }

    private static JsonObject news(boolean admin) throws SQLException {
        JsonArray articles = new JsonArray();
        try (Connection conn = Emulator.getDatabase().getDataSource().getConnection();
                PreparedStatement stmt = conn.prepareStatement("SELECT id,title,body,image,link_text,link_url,enabled,sort_order FROM ui_news "
                        + (admin ? "" : "WHERE enabled=1 ") + "ORDER BY sort_order ASC,id DESC LIMIT 50");
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                JsonObject article = new JsonObject();
                article.addProperty("id",rs.getInt("id"));
                article.addProperty("title",rs.getString("title")); article.addProperty("body",rs.getString("body"));
                String image=rs.getString("image"); article.addProperty("image",image != null && image.length()<=524288 ? image : "");
                article.addProperty("linkText",rs.getString("link_text")); article.addProperty("linkUrl",rs.getString("link_url"));
                if(admin){article.addProperty("enabled",rs.getBoolean("enabled"));article.addProperty("sortOrder",rs.getInt("sort_order"));}
                articles.add(article);
            }
        }
        JsonObject result = new JsonObject(); result.add("articles",articles); return result;
    }

    private static JsonObject error(String message) { JsonObject result = new JsonObject(); result.addProperty("error",message); return result; }
    private static void json(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus code, JsonObject body, boolean publicFeed) {
        send(ctx,req,code,body.toString(),"application/json; charset=utf-8",publicFeed);
    }
    private static void send(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus code, String body, String type, boolean publicFeed) {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,code,req.method()==HttpMethod.HEAD?Unpooled.EMPTY_BUFFER:Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE,type).setInt(HttpHeaderNames.CONTENT_LENGTH,code.equals(HttpResponseStatus.NO_CONTENT)?0:bytes.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL,"no-store").set("X-Content-Type-Options","nosniff").set("X-Frame-Options","DENY").set("Referrer-Policy","no-referrer");
        if(publicFeed){response.headers().set("Access-Control-Allow-Origin","*").set("Access-Control-Allow-Methods","GET, HEAD, OPTIONS");}
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
