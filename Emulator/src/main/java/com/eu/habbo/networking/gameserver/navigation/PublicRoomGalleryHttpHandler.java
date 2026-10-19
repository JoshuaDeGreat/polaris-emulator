package com.eu.habbo.networking.gameserver.navigation;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.modtool.HousekeepingAuditLog;
import com.eu.habbo.habbohotel.navigation.PublicRoomGallery;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.messages.incoming.housekeeping.HousekeepingGalleryAccess;
import com.eu.habbo.networking.gameserver.auth.AccessTokenService;
import com.eu.habbo.networking.gameserver.auth.CorsOriginGate;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Public pictures/listings plus authenticated, permission-checked Housekeeping writes. */
public final class PublicRoomGalleryHttpHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublicRoomGalleryHttpHandler.class);
    private static final String ADMIN = "/api/housekeeping/public-rooms";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (!(message instanceof FullHttpRequest request)) {
            super.channelRead(ctx, message);
            return;
        }
        String path = new QueryStringDecoder(request.uri()).path();
        if (!path.equals(PublicRoomGallery.BASE) && !path.startsWith(PublicRoomGallery.BASE + "/")
                && !path.equals(ADMIN) && !path.startsWith(ADMIN + "/")) {
            super.channelRead(ctx, message);
            return;
        }
        try {
            handle(ctx, request, path);
        } catch (IllegalArgumentException exception) {
            json(ctx, request, HttpResponseStatus.BAD_REQUEST, error("Check the room details and use a PNG or JPEG picture up to 256 KB."));
        } catch (Exception exception) {
            LOGGER.error("Public room gallery request failed: {}", path, exception);
            json(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("Unable to save or load public rooms. Please try again."));
        } finally {
            ReferenceCountUtil.release(request);
        }
    }

    private void handle(ChannelHandlerContext ctx, FullHttpRequest request, String path) throws Exception {
        if (request.method() == HttpMethod.OPTIONS) {
            send(ctx, request, HttpResponseStatus.NO_CONTENT, new byte[0], "application/json", false);
            return;
        }
        if (path.equals(PublicRoomGallery.BASE) && request.method() == HttpMethod.GET) {
            json(ctx, request, HttpResponseStatus.OK, PublicRoomGallery.list(false));
            return;
        }
        if (path.startsWith(PublicRoomGallery.BASE + "/images/") && (request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD)) {
            if (!PublicRoomGallery.validImagePath(path)) {
                json(ctx, request, HttpResponseStatus.NOT_FOUND, error("Picture not found."));
                return;
            }
            var file = PublicRoomGallery.imageDirectory().resolve(path.substring(path.lastIndexOf('/') + 1));
            if (!Files.isRegularFile(file)) {
                json(ctx, request, HttpResponseStatus.NOT_FOUND, error("Picture not found."));
                return;
            }
            send(ctx, request, HttpResponseStatus.OK, Files.readAllBytes(file), "image/png", true);
            return;
        }
        if (!path.equals(ADMIN) && !path.equals(ADMIN + "/image")) {
            json(ctx, request, HttpResponseStatus.NOT_FOUND, error("Not found."));
            return;
        }
        if (!CorsOriginGate.isAllowed(request)) {
            json(ctx, request, HttpResponseStatus.FORBIDDEN, error("Origin not allowed."));
            return;
        }
        String authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION, "");
        int userId = authorization.startsWith("Bearer ") ? AccessTokenService.verify(authorization.substring(7).trim()) : 0;
        Habbo operator = userId > 0 ? Emulator.getGameEnvironment().getHabboManager().getHabbo(userId) : null;
        if (!HousekeepingGalleryAccess.canUse(operator)) {
            json(ctx, request, HttpResponseStatus.FORBIDDEN, error("Housekeeping hotel permission is required. Stay logged into the hotel."));
            return;
        }
        if (path.equals(ADMIN) && request.method() == HttpMethod.GET) {
            json(ctx, request, HttpResponseStatus.OK, PublicRoomGallery.list(true));
            return;
        }
        if (request.method() != HttpMethod.POST) {
            json(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, error("Use GET or POST."));
            return;
        }
        if (request.content().readableBytes() > 360000) {
            json(ctx, request, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, error("Picture is too large."));
            return;
        }
        JsonObject body;
        try {
            body = JsonParser.parseString(request.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
            if (!body.has("roomId") || !body.get("roomId").isJsonPrimitive()) throw new IllegalArgumentException();
            if (path.endsWith("/image")) {
                if (!body.has("image") || !body.get("image").isJsonPrimitive()) throw new IllegalArgumentException();
            } else {
                for (String field : new String[] {"categoryId", "order", "image", "visible"}) {
                    if (!body.has(field) || !body.get(field).isJsonPrimitive()) throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid room details", exception);
        }
        int roomId = body.get("roomId").getAsInt();
        Room room = roomId > 0 ? Emulator.getGameEnvironment().getRoomManager().loadRoom(roomId, false) : null;
        if (!HousekeepingGalleryAccess.canEdit(operator, room)) {
            json(ctx, request, HttpResponseStatus.FORBIDDEN, error("Room does not exist or you cannot manage its owner."));
            return;
        }
        if (path.endsWith("/image")) {
            String encoded = body.get("image").getAsString();
            if (encoded.length() > 350000) throw new IllegalArgumentException("Image too large");
            String image = PublicRoomGallery.upload(Base64.getDecoder().decode(encoded));
            JsonObject result = new JsonObject();
            result.addProperty("image", image);
            audit(operator, room, "hotel.public_room.image", "Picture uploaded");
            json(ctx, request, HttpResponseStatus.OK, result);
        } else {
            int order = body.get("order").getAsInt();
            boolean visible = body.get("visible").getAsBoolean();
            PublicRoomGallery.save(roomId, body.get("categoryId").getAsInt(), visible, order, body.get("image").getAsString());
            audit(operator, room, "hotel.public_room.save", "visible=" + visible + " order=" + order);
            json(ctx, request, HttpResponseStatus.OK, PublicRoomGallery.list(true));
        }
    }

    private static void audit(Habbo operator, Room room, String action, String detail) {
        HousekeepingAuditLog.log(operator.getHabboInfo().getId(), operator.getHabboInfo().getUsername(), action,
                HousekeepingAuditLog.TARGET_ROOM, room.getId(), room.getName(), detail, operator.getHabboInfo().getIpLogin());
    }

    private static JsonObject error(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        return error;
    }

    private static void json(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, JsonObject body) {
        send(ctx, request, status, body.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", false);
    }

    private static void send(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, byte[] bytes, String type, boolean immutable) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                request.method() == HttpMethod.HEAD ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, immutable ? "public, max-age=31536000, immutable" : "no-store");
        response.headers().set("X-Content-Type-Options", "nosniff");
        response.headers().set("Vary", "Origin");
        if (CorsOriginGate.isAllowed(request)) {
            response.headers().set("Access-Control-Allow-Origin", request.headers().get(HttpHeaderNames.ORIGIN));
            response.headers().set("Access-Control-Allow-Credentials", "true");
            response.headers().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            response.headers().set("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS");
        }
        if (HttpUtil.isKeepAlive(request)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
