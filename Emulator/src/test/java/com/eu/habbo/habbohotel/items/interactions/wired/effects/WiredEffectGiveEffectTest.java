package com.eu.habbo.habbohotel.items.interactions.wired.effects;

import static com.eu.habbo.habbohotel.items.interactions.wired.effects.WiredEffectTestFixtures.base;
import static com.eu.habbo.habbohotel.items.interactions.wired.effects.WiredEffectTestFixtures.row;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.eu.habbo.habbohotel.items.interactions.wired.WiredSettings;
import com.eu.habbo.habbohotel.wired.WiredEffectType;
import com.eu.habbo.habbohotel.wired.protocol.WiredActionCodes;
import org.junit.jupiter.api.Test;

/** Official give effect (52): [effect id, priority, type], and the rows the text editor left. */
class WiredEffectGiveEffectTest {

    @Test
    void savesAndSendsBackTheOfficialLayoutUnderCode52() throws Exception {
        WiredEffectGiveEffect box = new WiredEffectGiveEffect(1, 1, base(), "", 0, 0);

        box.saveData(new WiredSettings(new int[] {20_000, 7, 1}, "", new int[0], 0), null);

        var body = WiredEffectProgressAchievementTest.body(box);
        assertArrayEquals(new int[] {10_000, 2, 1}, body.params());
        assertEquals(52, body.code());
        assertEquals(52, WiredActionCodes.official(WiredEffectType.EFFECT_ID.code));
    }

    @Test
    void aRowFromTheTextEditorKeepsItsEffect() throws Exception {
        WiredEffectGiveEffect box = new WiredEffectGiveEffect(1, 1, base(), "", 0, 0);

        box.loadWiredData(row("{\"message\":\"108\",\"delay\":2,\"userSource\":200}"), null);

        assertEquals(108, box.getEffectId());
        assertArrayEquals(
                new int[] {108, 2, 0},
                WiredEffectProgressAchievementTest.body(box).params());
    }

    @Test
    void giveHandItemHasItsOwnCodeNow() {
        assertEquals(1157, WiredActionCodes.official(WiredEffectType.GIVE_HAND_ITEM.code));
    }
}
