package com.eu.habbo.habbohotel.wired.protocol;

/**
 * A selector that writes the official definition itself: its sources and its filter and invert
 * switches go in the official fields instead of its int params.
 */
public interface WiredOfficialSelector {
    int[] officialFurniSources();

    int[] officialUserSources();

    boolean selectorFilter();

    boolean selectorInvert();
}
