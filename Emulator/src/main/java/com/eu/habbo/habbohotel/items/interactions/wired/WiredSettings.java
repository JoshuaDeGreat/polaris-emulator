package com.eu.habbo.habbohotel.items.interactions.wired;

import com.eu.habbo.habbohotel.rooms.Room;

public class WiredSettings {
    private int[] intParams;
    private String stringParam;
    private int[] furniIds;
    private int stuffTypeSelectionCode;
    private int delay;
    private Room room;
    // Official AIR save fields (build WIN63-202610131318); empty until a box reads them.
    private int[] furniSourceTypes = new int[0];
    private int[] userSourceTypes = new int[0];
    private String[] variableIds = new String[0];
    private int[] furniIds2 = new int[0];
    private int quantifierCode;
    private boolean selectorFilter;
    private boolean selectorInvert;

    public WiredSettings(int[] intParams, String stringParam, int[] furniIds, int stuffTypeSelectionCode, int delay) {
        this.furniIds = furniIds;
        this.intParams = intParams;
        this.stringParam = stringParam;
        this.stuffTypeSelectionCode = stuffTypeSelectionCode;
        this.delay = delay;
    }

    public WiredSettings(int[] intParams, String stringParam, int[] furniIds, int stuffTypeSelectionCode) {
        this(intParams, stringParam, furniIds, stuffTypeSelectionCode, 0);
    }

    public int getStuffTypeSelectionCode() {
        return stuffTypeSelectionCode;
    }

    public void setStuffTypeSelectionCode(int stuffTypeSelectionCode) {
        this.stuffTypeSelectionCode = stuffTypeSelectionCode;
    }

    public int[] getFurniIds() {
        return furniIds;
    }

    public void setFurniIds(int[] furniIds) {
        this.furniIds = furniIds;
    }

    public String getStringParam() {
        return stringParam;
    }

    public void setStringParam(String stringParam) {
        this.stringParam = stringParam;
    }

    public int[] getIntParams() {
        return intParams;
    }

    public void setIntParams(int[] intParams) {
        this.intParams = intParams;
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }

    public Room getRoom() {
        return this.room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public int[] getFurniSourceTypes() {
        return this.furniSourceTypes;
    }

    public void setFurniSourceTypes(int[] furniSourceTypes) {
        this.furniSourceTypes = (furniSourceTypes != null) ? furniSourceTypes : new int[0];
    }

    public int[] getUserSourceTypes() {
        return this.userSourceTypes;
    }

    public void setUserSourceTypes(int[] userSourceTypes) {
        this.userSourceTypes = (userSourceTypes != null) ? userSourceTypes : new int[0];
    }

    public String[] getVariableIds() {
        return this.variableIds;
    }

    public void setVariableIds(String[] variableIds) {
        this.variableIds = (variableIds != null) ? variableIds : new String[0];
    }

    /** The second furni pick list (official dual picking). */
    public int[] getFurniIds2() {
        return this.furniIds2;
    }

    public void setFurniIds2(int[] furniIds2) {
        this.furniIds2 = (furniIds2 != null) ? furniIds2 : new int[0];
    }

    /** A condition's all/any quantifier code. */
    public int getQuantifierCode() {
        return this.quantifierCode;
    }

    public void setQuantifierCode(int quantifierCode) {
        this.quantifierCode = quantifierCode;
    }

    /** A selector's official "filter" switch. */
    public boolean isSelectorFilter() {
        return this.selectorFilter;
    }

    public void setSelectorFilter(boolean selectorFilter) {
        this.selectorFilter = selectorFilter;
    }

    /** A selector's official "invert" switch. */
    public boolean isSelectorInvert() {
        return this.selectorInvert;
    }

    public void setSelectorInvert(boolean selectorInvert) {
        this.selectorInvert = selectorInvert;
    }
}
