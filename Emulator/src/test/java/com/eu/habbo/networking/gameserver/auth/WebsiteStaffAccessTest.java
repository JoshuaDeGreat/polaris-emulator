package com.eu.habbo.networking.gameserver.auth;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WebsiteStaffAccessTest {
 @Test void explicitDenialsOverrideStaffRank(){assertFalse(WebsiteStaffAccess.permissionDecision(true,0));assertFalse(WebsiteStaffAccess.permissionDecision(true,2));}
 @Test void onlyExplicitGrantOrExistingRankCanAllow(){assertTrue(WebsiteStaffAccess.permissionDecision(false,1));assertFalse(WebsiteStaffAccess.permissionDecision(false,null));assertTrue(WebsiteStaffAccess.permissionDecision(true,null));}
 @Test void anonymousUsersNeverAcquireOfflineStaffAccess()throws Exception{assertNull(WebsiteStaffAccess.find(0));assertNull(WebsiteStaffAccess.find(-1));}
}
