package com.eu.habbo.habbohotel.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eu.habbo.habbohotel.items.FurnitureType;
import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.messages.ServerMessage;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CatalogPurchaseAvailabilityTest {
    @Test
    void trimmedBalloonCanBeBoughtWithoutEnablingMultiBuy() throws Exception {
        CatalogItem offer = offer("habbo20_r20_crackable", false, 0);
        doReturn(Set.of(furniture("habbo20_r20_crackable"))).when(offer).getBaseItems();
        assertFlags(offer, false, true);
    }

    @Test
    void roomBundleCanBeBoughtWithoutEnablingMultiBuy() throws Exception {
        CatalogItem offer = offer("rotating_roomdeal286", false, 0);
        doReturn(Set.of(furniture("rare_chair"), furniture("rare_table")))
                .when(offer)
                .getBaseItems();
        offer.getBundle().put(1, 10);
        offer.getBundle().put(2, 5);
        assertFlags(offer, false, true);
    }

    @Test
    void staffRareWithMultiBuyOffRemainsPurchasable() throws Exception {
        CatalogItem offer = offer("staff_rare", false, 0);
        doReturn(Set.of(furniture("rare_dragon"))).when(offer).getBaseItems();
        assertFlags(offer, false, true);
    }

    @Test
    void ordinaryDiscountedOfferRetainsBothFlags() throws Exception {
        CatalogItem offer = offer("chair", true, 0);
        doReturn(Set.of(furniture("chair"))).when(offer).getBaseItems();
        assertFlags(offer, true, true);
    }

    @Test
    void limitedRareKeepsStockAndDisablesMultiBuyOnly() throws Exception {
        CatalogItem offer = offer("limited_rare", false, 0);
        offer.limitedStack = 50;
        doReturn(3).when(offer).getLimitedSells();
        doReturn(Set.of(furniture("rare_dragon"))).when(offer).getBaseItems();
        ServerMessage message = assertFlags(offer, false, true);
        verify(message).appendInt(50);
        verify(message).appendInt(47);
    }

    @Test
    void unresolvedOfferRemainsUnavailableEvenWithMultiBuyFlag() throws Exception {
        CatalogItem offer = offer("missing_furniture", true, 0);
        doReturn(Set.of()).when(offer).getBaseItems();
        assertFlags(offer, true, false);
    }

    @Test
    void habbiconWithoutFurnitureRemainsPurchasable() throws Exception {
        assertFlags(offer("habbicon", false, 42), false, true);
    }

    private CatalogItem offer(String name, boolean multiBuy, int habbiconId) throws Exception {
        ResultSet row = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(row.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("habbicon_id");
        when(row.getInt("habbicon_id")).thenReturn(habbiconId);
        when(row.getString("item_Ids")).thenReturn("");
        when(row.getString("catalog_name")).thenReturn(name);
        when(row.getString("extradata")).thenReturn("");
        when(row.getInt("amount")).thenReturn(1);
        when(row.getBoolean("have_offer")).thenReturn(multiBuy);
        return spy(new CatalogItem(row));
    }

    private Item furniture(String name) {
        Item item = mock(Item.class);
        when(item.getName()).thenReturn(name);
        when(item.getType()).thenReturn(FurnitureType.FLOOR);
        return item;
    }

    private ServerMessage assertFlags(CatalogItem offer, boolean multiBuy, boolean purchasable) {
        ServerMessage message = mock(ServerMessage.class);
        offer.serialize(message);
        ArgumentCaptor<Boolean> flags = ArgumentCaptor.forClass(Boolean.class);
        verify(message, atLeastOnce()).appendBoolean(flags.capture());
        List<Boolean> values = flags.getAllValues();
        // The existing wire tail is bundlePurchaseAllowed, isPet, haveOffer.
        assertEquals(List.of(multiBuy, false, purchasable), values.subList(values.size() - 3, values.size()));
        return message;
    }
}
