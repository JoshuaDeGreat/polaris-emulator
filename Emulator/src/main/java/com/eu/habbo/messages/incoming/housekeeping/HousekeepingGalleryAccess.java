package com.eu.habbo.messages.incoming.housekeeping;

import com.eu.habbo.habbohotel.permissions.Permission;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.users.Habbo;

/** The HTTP gallery tools follow the same panel, lockdown and room-owner gates. */
public final class HousekeepingGalleryAccess {
    private HousekeepingGalleryAccess() {}

    public static boolean canUse(Habbo operator) {
        return operator != null && operator.getClient() != null
                && operator.hasPermission(Permission.ACC_HOUSEKEEPING)
                && operator.hasPermission(HousekeepingAreas.HOTEL)
                && (!HousekeepingLockdown.isLocked() || HousekeepingTargetRankGuard.isTopRank(operator));
    }

    public static boolean canEdit(Habbo operator, Room room) {
        return canUse(operator) && HousekeepingRoomGuard.canManageRoom(operator, room);
    }
}
