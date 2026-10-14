package com.eu.habbo.habbohotel.wired.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.GameEnvironment;
import com.eu.habbo.habbohotel.items.WiredInteractionRegistryFixture;
import com.eu.habbo.habbohotel.items.interactions.InteractionWired;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredCondition;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredTrigger;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.messages.ServerMessage;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

/**
 * Every registered wired box's definition converts to the official layout: the legacy fields parse
 * with nothing unexpected left over, and the result reads back exactly as the official client reads it.
 */
class WiredDefinitionWireCoverageTest {
    /** A legacy condition ends with two unused ints; a legacy trigger sometimes with one. */
    private static final Set<Integer> TRIGGER_LEFTOVERS = Set.of(0, 4);

    @Test
    void everyRegisteredBoxConvertsToTheOfficialLayout() throws Exception {
        List<String> failures = new ArrayList<>();

        try (MockedStatic<Emulator> emulator = mockStatic(Emulator.class)) {
            emulator.when(Emulator::getGameEnvironment)
                    .thenReturn(mock(GameEnvironment.class, Answers.RETURNS_DEEP_STUBS));

            for (Class<? extends InteractionWired> type : WiredInteractionRegistryFixture.wiredTypes().stream()
                    .sorted(Comparator.comparing(Class::getName))
                    .toList()) {
                WiredDefinitionWire.Holder holder = holderOf(type);
                try {
                    InteractionWired item = WiredInteractionRegistryFixture.instantiate(type);
                    ServerMessage legacy = new ServerMessage(1);
                    item.serializeWiredData(legacy, mock(Room.class, Answers.RETURNS_DEEP_STUBS));

                    ByteBuf body = legacy.get();
                    body.skipBytes(6);
                    WiredDefinitionWire.Definition definition = WiredDefinitionWire.readLegacy(body, holder);
                    body.release();

                    boolean expectedLeftover =
                            switch (holder) {
                                case ACTION -> definition.leftover == 0;
                                case CONDITION -> definition.leftover == 8;
                                case TRIGGER -> TRIGGER_LEFTOVERS.contains(definition.leftover);
                            };
                    if (!expectedLeftover) failures.add(type.getSimpleName() + " left " + definition.leftover);

                    ServerMessage official = new ServerMessage(2);
                    WiredDefinitionWire.writeOfficial(definition, holder, official);
                    ByteBuf written = official.get();
                    written.skipBytes(6);
                    OfficialReader.read(written, holder);
                    if (written.isReadable()) failures.add(type.getSimpleName() + " official trailing bytes");
                    written.release();
                } catch (Throwable failure) {
                    failures.add(type.getSimpleName() + " " + WiredInteractionRegistryFixture.rootCause(failure));
                }
            }
        }

        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void theOfficialLayoutKeepsEveryLegacyField() {
        WiredDefinitionWire.Definition definition = new WiredDefinitionWire.Definition();
        definition.furniLimit = 20;
        definition.stuffIds = new int[] {7, 8};
        definition.spriteId = 99;
        definition.id = 1234;
        definition.stringParam = "hi";
        definition.intParams = new int[] {1, 2, 3};
        definition.code = 5;
        definition.delay = 6;

        ServerMessage official = new ServerMessage(2);
        WiredDefinitionWire.writeOfficial(definition, WiredDefinitionWire.Holder.ACTION, official);
        ByteBuf in = official.get();
        in.skipBytes(6);

        assertEquals(20, in.readInt());
        assertEquals(2, in.readInt());
        assertEquals(7, in.readInt());
        assertEquals(8, in.readInt());
        assertEquals(0, in.readInt(), "stuffIds2");
        assertEquals(99, in.readInt());
        assertEquals(1234, in.readInt());
        assertEquals("hi", OfficialReader.string(in));
        assertEquals(3, in.readInt());
        in.skipBytes(12);
        assertEquals(0, in.readInt(), "variableIds");
        assertEquals(0, in.readInt(), "furni sources");
        assertEquals(0, in.readInt(), "user sources");
        assertEquals(5, in.readInt(), "code");
        assertEquals(6, in.readInt(), "delay");
        assertFalse(in.readBoolean(), "advanced mode");
        in.release();
    }

    private static WiredDefinitionWire.Holder holderOf(Class<? extends InteractionWired> type) {
        if (InteractionWiredTrigger.class.isAssignableFrom(type)) return WiredDefinitionWire.Holder.TRIGGER;
        if (InteractionWiredCondition.class.isAssignableFrom(type)) return WiredDefinitionWire.Holder.CONDITION;
        return WiredDefinitionWire.Holder.ACTION;
    }

    /** The official client's read order (AS3 §_-lk§ and its holder subclasses). */
    static final class OfficialReader {
        static void read(ByteBuf in, WiredDefinitionWire.Holder holder) {
            in.readInt();
            ints(in);
            ints(in);
            in.readInt();
            in.readInt();
            string(in);
            ints(in);
            int variables = in.readInt();
            for (int i = 0; i < variables; i++) string(in);
            ints(in);
            ints(in);
            in.readInt();
            if (holder != WiredDefinitionWire.Holder.TRIGGER) in.readInt();
            in.readBoolean();
            for (int slot = 0; slot < 2; slot++) {
                int lists = in.readInt();
                for (int i = 0; i < lists; i++) ints(in);
            }
            ints(in);
            ints(in);
            in.readBoolean();
            if (holder == WiredDefinitionWire.Holder.CONDITION) {
                in.readByte();
                in.readBoolean();
            }
            contexts(in);
            ints(in);
        }

        /** Only the variable leaderboard context (type 8) is sent so far. */
        private static void contexts(ByteBuf in) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int type = in.readInt();
                if (type != 8) throw new IllegalStateException("context type " + type);
                in.readInt();
                in.readInt();
                in.readInt();
                in.readInt();
                int variables = in.readInt();
                for (int v = 0; v < variables; v++) {
                    string(in);
                    string(in);
                    in.readBoolean();
                    in.readBoolean();
                    in.readInt();
                }
            }
        }

        private static void ints(ByteBuf in) {
            int count = in.readInt();
            in.skipBytes(count * 4);
        }

        static String string(ByteBuf in) {
            byte[] bytes = new byte[in.readUnsignedShort()];
            in.readBytes(bytes);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
