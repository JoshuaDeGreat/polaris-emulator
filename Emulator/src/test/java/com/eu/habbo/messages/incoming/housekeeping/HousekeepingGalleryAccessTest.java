package com.eu.habbo.messages.incoming.housekeeping;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.eu.habbo.habbohotel.permissions.Permission;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.habbohotel.gameclients.GameClient;
import org.junit.jupiter.api.Test;

class HousekeepingGalleryAccessTest {
    @Test
    void requiresAnOnlineOperatorWithBothPermissions() {
        assertFalse(HousekeepingGalleryAccess.canUse(null));
        Habbo operator = mock(Habbo.class);
        assertFalse(HousekeepingGalleryAccess.canUse(operator));
        when(operator.getClient()).thenReturn(mock(GameClient.class));
        assertFalse(HousekeepingGalleryAccess.canUse(operator));
        when(operator.hasPermission(Permission.ACC_HOUSEKEEPING)).thenReturn(true);
        assertFalse(HousekeepingGalleryAccess.canUse(operator));
        when(operator.hasPermission(HousekeepingAreas.HOTEL)).thenReturn(true);
        try (var lockdown = mockStatic(HousekeepingLockdown.class)) {
            lockdown.when(HousekeepingLockdown::isLocked).thenReturn(false);
            assertTrue(HousekeepingGalleryAccess.canUse(operator));
        }
    }

    @Test
    void honoursEmergencyHousekeepingLockdown() {
        Habbo operator = mock(Habbo.class);
        when(operator.getClient()).thenReturn(mock(GameClient.class));
        when(operator.hasPermission(anyString())).thenReturn(true);
        try (var lockdown = mockStatic(HousekeepingLockdown.class); var ranks = mockStatic(HousekeepingTargetRankGuard.class)) {
            lockdown.when(HousekeepingLockdown::isLocked).thenReturn(true);
            ranks.when(() -> HousekeepingTargetRankGuard.isTopRank(operator)).thenReturn(false);
            assertFalse(HousekeepingGalleryAccess.canUse(operator));
            ranks.when(() -> HousekeepingTargetRankGuard.isTopRank(operator)).thenReturn(true);
            assertTrue(HousekeepingGalleryAccess.canUse(operator));
        }
    }
}
