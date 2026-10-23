package com.eu.habbo.networking.gameserver.auth;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class WebsiteSessionServiceTest {
 @Test void malformedSessionsNeverReachTheDatabase(){for(String token:new String[]{"","hws_bad","hws_"+"!".repeat(43),"A".repeat(3000)})assertEquals(0,WebsiteSessionService.verify(token));assertEquals(0,WebsiteSessionService.verify(null));}
 @Test void only256BitUrlSafeTokensHaveValidFormat(){assertTrue(WebsiteSessionService.validFormat("hws_"+"A".repeat(43)));assertFalse(WebsiteSessionService.validFormat("hws_"+"A".repeat(42)));}
 @Test void storageUsesDigestsRatherThanRawTokens(){String token="hws_"+"A".repeat(43);String hash=WebsiteSessionService.digest(token);assertEquals(64,hash.length());assertNotEquals(token,hash);assertNotEquals(hash,WebsiteSessionService.digest(token+"B"));}
}
