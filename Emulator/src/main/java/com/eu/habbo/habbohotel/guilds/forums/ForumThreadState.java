package com.eu.habbo.habbohotel.guilds.forums;

public enum ForumThreadState {
    OPEN(0),
    CLOSED(1),
    // The two names are swapped against the official client (10 = hidden by a group admin,
    // 20 = hidden by staff); the values are what count, the names stay for plugins.
    HIDDEN_BY_STAFF_MEMBER(10),
    HIDDEN_BY_GUILD_ADMIN(20);

    private final int stateId;

    ForumThreadState(int stateId) {
        this.stateId = stateId;
    }

    public static ForumThreadState fromValue(int value) {
        for (ForumThreadState state : ForumThreadState.values()) {
            if (state.stateId == value) {
                return state;
            }
        }

        return CLOSED;
    }

    public int getStateId() {
        return this.stateId;
    }
}
