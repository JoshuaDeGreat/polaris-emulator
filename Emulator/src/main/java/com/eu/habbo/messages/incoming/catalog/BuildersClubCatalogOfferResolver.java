package com.eu.habbo.messages.incoming.catalog;

import com.eu.habbo.habbohotel.catalog.CatalogItem;
import com.eu.habbo.habbohotel.catalog.CatalogManager;
import com.eu.habbo.habbohotel.catalog.CatalogPage;
import com.eu.habbo.habbohotel.catalog.CatalogPageType;

/**
 * Builders Club placement creates room furniture without taking a catalogue payment.
 * Only offers from the Builders Club catalogue may use this privileged operation.
 */
final class BuildersClubCatalogOfferResolver {
    private BuildersClubCatalogOfferResolver() {}

    static CatalogItem resolve(CatalogManager catalog, int pageId, int offerId) {
        if (catalog == null || offerId <= 0 || pageId < -1) return null;

        // Never resolve via the normal catalogue or its offerDefs mapping:
        // doing so lets players create paid furniture without buying it.
        CatalogItem item = catalog.getCatalogItem(offerId, CatalogPageType.BUILDER);
        if (item == null || item.getOfferId() != offerId) return null;

        CatalogPage sourcePage = catalog.getCatalogPage(item.getPageId(), CatalogPageType.BUILDER);
        if (sourcePage == null || !sourcePage.isEnabled() || !sourcePage.isVisible()) return null;

        // -1 is the Builders Club search-result sentinel; otherwise require
        // the offer to actually be present on the selected Builders Club page.
        if (pageId == -1) return item;
        CatalogPage requestedPage =
                pageId == item.getPageId()
                        ? sourcePage
                        : catalog.getCatalogPage(pageId, CatalogPageType.BUILDER);

        if (requestedPage == null || !requestedPage.isEnabled() || !requestedPage.isVisible()) return null;
        if (!requestedPage.getCatalogItems().containsKey(item.getId())) return null;

        return item;
    }
}
