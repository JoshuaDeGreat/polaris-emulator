package com.eu.habbo.networking.gameserver.auth;

import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommunityEndpointsTest {
    private FullHttpResponse request(HttpMethod method, String path, String origin) {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                FullHttpRequest req = (FullHttpRequest) msg;
                try { CommunityEndpoints.handle(ctx, req, path); } finally { req.release(); }
            }
        });
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,method,path);
        req.headers().set(HttpHeaderNames.HOST,"hotel.example");
        if(origin!=null)req.headers().set(HttpHeaderNames.ORIGIN,origin);
        channel.writeInbound(req);
        FullHttpResponse response=channel.readOutbound();
        assertNotNull(response);
        channel.finishAndReleaseAll();
        return response;
    }
    @Test void publicFeedIsReadOnlyAndCorsDoesNotPermitWrites() {
        FullHttpResponse response=request(HttpMethod.POST,"/api/community/status","https://website.example");
        try { assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED,response.status());
            assertEquals("*",response.headers().get("Access-Control-Allow-Origin"));
            assertFalse(response.headers().get("Access-Control-Allow-Methods").contains("POST"));
        } finally {response.release();}
    }
    @Test void staffWritesRejectForeignOriginBeforeAuthentication() {
        FullHttpResponse response=request(HttpMethod.POST,"/api/community/staff/news","https://attacker.example");
        try {assertEquals(HttpResponseStatus.FORBIDDEN,response.status());assertNull(response.headers().get("Access-Control-Allow-Origin"));}
        finally {response.release();}
    }
    @Test void staffListingRejectsAnonymousRequests() {
        FullHttpResponse response=request(HttpMethod.GET,"/api/community/staff/news",null);
        try {assertEquals(HttpResponseStatus.FORBIDDEN,response.status());}finally {response.release();}
    }
    @Test void staffPageIsServedWithoutExposingNewsOrTokens() {
        FullHttpResponse response=request(HttpMethod.GET,"/community/staff",null);
        try {assertEquals(HttpResponseStatus.OK,response.status());assertEquals("DENY",response.headers().get("X-Frame-Options"));
            assertEquals("no-store",response.headers().get(HttpHeaderNames.CACHE_CONTROL));}
        finally {response.release();}
    }
    @Test void originMustMatchHttpsAuthorityExactly() {
        assertTrue(CommunityEndpoints.sameOrigin("https://hotel.example","hotel.example"));
        assertFalse(CommunityEndpoints.sameOrigin("http://hotel.example","hotel.example"));
        assertFalse(CommunityEndpoints.sameOrigin("https://hotel.example.attacker.example","hotel.example"));
        assertFalse(CommunityEndpoints.sameOrigin(null,"hotel.example"));
        assertFalse(CommunityEndpoints.sameOrigin("https://hotel.example:443","hotel.example:444"));
    }
    @Test void draftAndArticleBoundsAreValidated() {
        JsonObject article=new JsonObject();article.addProperty("id",0);article.addProperty("title","Heaven news");
        article.addProperty("body","A new chapter.");article.addProperty("sortOrder",0);article.addProperty("enabled",false);
        assertDoesNotThrow(()->CommunityEndpoints.validateArticle(article));
        article.addProperty("image","javascript:alert(1)");assertThrows(IllegalArgumentException.class,()->CommunityEndpoints.validateArticle(article));
        article.addProperty("image","https://images.example/news.png");assertDoesNotThrow(()->CommunityEndpoints.validateArticle(article));
        article.addProperty("linkUrl","https://user:secret@example.com/");assertThrows(IllegalArgumentException.class,()->CommunityEndpoints.validateArticle(article));
        article.addProperty("linkUrl","");article.addProperty("body","x".repeat(20001));assertThrows(IllegalArgumentException.class,()->CommunityEndpoints.validateArticle(article));
    }
}
