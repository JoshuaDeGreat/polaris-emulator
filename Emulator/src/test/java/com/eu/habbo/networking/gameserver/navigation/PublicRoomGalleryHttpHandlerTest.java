package com.eu.habbo.networking.gameserver.navigation;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;

class PublicRoomGalleryHttpHandlerTest {
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
