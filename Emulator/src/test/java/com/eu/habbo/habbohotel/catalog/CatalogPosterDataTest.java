package com.eu.habbo.habbohotel.catalog;

import com.eu.habbo.habbohotel.items.FurnitureType;
import com.eu.habbo.habbohotel.items.Item;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogPosterDataTest {
    private CatalogItem offer(String name, FurnitureType type, String design) {
        Item base = mock(Item.class);
        when(base.getName()).thenReturn(name);
        when(base.getType()).thenReturn(type);
        CatalogItem offer = mock(CatalogItem.class);
        when(offer.getBaseItems()).thenReturn(Set.of(base));
        when(offer.getExtradata()).thenReturn(design);
        return offer;
    }

    @Test void emptyClientDataKeepsTheSelectedPoster() {
        assertEquals("12", CatalogPosterData.resolve(offer("poster", FurnitureType.WALL, "12"), ""));
        assertEquals("12", CatalogPosterData.resolve(offer("poster", FurnitureType.WALL, "12"), null));
    }
    @Test void catalogueDesignOverridesAClientSuppliedDifferentPoster() {
        assertEquals("12", CatalogPosterData.resolve(offer("poster", FurnitureType.WALL, "12"), "500"));
    }
    @Test void unrelatedFurnitureKeepsItsCustomData() {
        assertEquals("hello", CatalogPosterData.resolve(offer("post_it", FurnitureType.WALL, "12"), "hello"));
        assertEquals("red", CatalogPosterData.resolve(offer("chair", FurnitureType.FLOOR, "12"), "red"));
    }
    @Test void legacyOffersWithoutADesignRemainCompatible() {
        assertEquals("7", CatalogPosterData.resolve(offer("poster", FurnitureType.WALL, ""), "7"));
        assertEquals("7", CatalogPosterData.resolve(offer("poster", FurnitureType.WALL, null), "7"));
    }
}
