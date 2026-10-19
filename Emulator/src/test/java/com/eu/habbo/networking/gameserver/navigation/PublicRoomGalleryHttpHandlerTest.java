package com.eu.habbo.networking.gameserver.navigation;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import com.eu.habbo.networking.gameserver.handlers.WebSocketHttpCleanupHandler;
import org.junit.jupiter.api.Test;

class PublicRoomGalleryHttpHandlerTest {
    @Test
    void galleryHandlersAreRemovedAfterTheGameConnectionUpgrades() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.pipeline().addLast("blockingHttpAdmissionPublicRooms", new ChannelInboundHandlerAdapter());
            channel.pipeline().addLast("publicRoomGalleryHttpHandler", new PublicRoomGalleryHttpHandler());
            channel.pipeline().addLast("wsHttpCleanup", new WebSocketHttpCleanupHandler());
            channel.pipeline().fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete("/", new DefaultHttpHeaders(), null));
            assertNull(channel.pipeline().get("blockingHttpAdmissionPublicRooms"));
            assertNull(channel.pipeline().get("publicRoomGalleryHttpHandler"));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void managementRequestsWithoutAnAllowedOriginCannotWrite() {
        EmbeddedChannel channel = new EmbeddedChannel(new PublicRoomGalleryHttpHandler());
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/housekeeping/public-rooms"));
            FullHttpResponse response = channel.readOutbound();
            try { assertEquals(HttpResponseStatus.FORBIDDEN, response.status()); }
            finally { response.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void imagePathsCannotEscapeTheImageDirectory() {
        EmbeddedChannel channel = new EmbeddedChannel(new PublicRoomGalleryHttpHandler());
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/navigator/public-rooms/images/../../config.ini"));
            FullHttpResponse response = channel.readOutbound();
            try { assertEquals(HttpResponseStatus.NOT_FOUND, response.status()); }
            finally { response.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }
}
