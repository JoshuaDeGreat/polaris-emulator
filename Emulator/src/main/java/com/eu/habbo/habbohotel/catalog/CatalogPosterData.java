package com.eu.habbo.habbohotel.catalog;

import com.eu.habbo.habbohotel.items.FurnitureType;

/** Classic posters share one base item; the catalogue offer selects their artwork. */
public final class CatalogPosterData {
    private CatalogPosterData() {}

    public static String resolve(CatalogItem offer, String requested) {
        if (offer == null || offer.getBaseItems().size() != 1) return requested;
        var base = offer.getBaseItems().iterator().next();
        if (base.getType() != FurnitureType.WALL || !"poster".equalsIgnoreCase(base.getName())) return requested;
        String design = offer.getExtradata();
        if (design == null || !design.trim().matches("[1-9][0-9]{0,5}")) return requested;
        return design.trim();
    }
}
