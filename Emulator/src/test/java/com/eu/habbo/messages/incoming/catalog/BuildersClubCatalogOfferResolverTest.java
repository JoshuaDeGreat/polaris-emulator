package com.eu.habbo.messages.incoming.catalog;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.eu.habbo.habbohotel.catalog.CatalogItem;
import com.eu.habbo.habbohotel.catalog.CatalogManager;
import com.eu.habbo.habbohotel.catalog.CatalogPage;
import com.eu.habbo.habbohotel.catalog.CatalogPageType;
import org.junit.jupiter.api.Test;

class BuildersClubCatalogOfferResolverTest {
    private final CatalogManager catalog = mock(CatalogManager.class);
    private final CatalogPage page = mock(CatalogPage.class);
    private final CatalogItem item = mock(CatalogItem.class);

    private void addValidBuilderOffer() {
        when(item.getOfferId()).thenReturn(42);
        when(item.getPageId()).thenReturn(7);
        when(item.getId()).thenReturn(90);
        when(catalog.getCatalogItem(42, CatalogPageType.BUILDER)).thenReturn(item);
        when(catalog.getCatalogPage(7, CatalogPageType.BUILDER)).thenReturn(page);
        when(page.isEnabled()).thenReturn(true);
        when(page.isVisible()).thenReturn(true);
    }

    @Test
    void validBuilderSearchOfferIsAccepted() {
        addValidBuilderOffer();
        assertSame(item, BuildersClubCatalogOfferResolver.resolve(catalog, -1, 42));
    }

    @Test
    void regularCatalogueOfferCannotCreateFreeFurniture() {
        when(catalog.getCatalogItem(42)).thenReturn(item);
        assertNull(BuildersClubCatalogOfferResolver.resolve(catalog, -1, 42));
    }

    @Test
    void hiddenAndDisabledBuilderPagesAreRejected() {
        addValidBuilderOffer();
        when(page.isVisible()).thenReturn(false);
        assertNull(BuildersClubCatalogOfferResolver.resolve(catalog, -1, 42));
        when(page.isVisible()).thenReturn(true);
        when(page.isEnabled()).thenReturn(false);
        assertNull(BuildersClubCatalogOfferResolver.resolve(catalog, -1, 42));
    }

    @Test
    void offerIdMustMatchTheActualBuilderOffer() {
        addValidBuilderOffer();
        when(item.getOfferId()).thenReturn(999);
        assertNull(BuildersClubCatalogOfferResolver.resolve(catalog, -1, 42));
    }

    @Test
    void invalidPageAndOfferIdsAreRejected() {
        assertNull(BuildersClubCatalogOfferResolver.resolve(catalog, -2, 42));
        assertNull(BuildersClubCatalogOfferResolver.resolve(catalog, -1, 0));
    }

    @Test
    void mismatchedBuilderPageIsRejected() {
        addValidBuilderOffer();
        assertNull(BuildersClubCatalogOfferResolver.resolve(catalog, 8, 42));
    }
}
