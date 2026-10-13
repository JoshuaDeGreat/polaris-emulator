package com.eu.habbo.habbohotel.wired.leaderboard;

import java.util.List;

/**
 * One leaderboard panel as the client is told about it (the official snapshot descriptor): where it
 * stands, how it looks and which variables it can show. {@code revision} changes whenever the rows
 * it would list change meaning, so the client drops what it has and asks again.
 */
public record WiredLeaderboardDescriptor(
        int overlayId,
        int revision,
        String title,
        String description,
        int tileX,
        int tileY,
        int offsetX,
        int altitude,
        int sortMode,
        List<WiredLeaderboardConfig.Column> columns,
        List<Choice> choices,
        String defaultVariableId,
        int pageSizeOption) {

    /** A variable the panel can show, and the name its list shows it by. */
    public record Choice(String variableId, String displayName) {}

    public WiredLeaderboardDescriptor {
        columns = List.copyOf(columns);
        choices = List.copyOf(choices);
    }

    public boolean offers(String variableId) {
        for (Choice choice : this.choices) {
            if (choice.variableId().equals(variableId)) {
                return true;
            }
        }
        return false;
    }

    public WiredLeaderboardDescriptor withRevision(int revision) {
        return new WiredLeaderboardDescriptor(
                this.overlayId,
                revision,
                this.title,
                this.description,
                this.tileX,
                this.tileY,
                this.offsetX,
                this.altitude,
                this.sortMode,
                this.columns,
                this.choices,
                this.defaultVariableId,
                this.pageSizeOption);
    }
}
