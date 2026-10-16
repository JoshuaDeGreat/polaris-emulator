package com.eu.habbo.habbohotel.items.interactions.wired.effects;

import com.eu.habbo.habbohotel.gameclients.GameClient;
import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredEffect;
import com.eu.habbo.habbohotel.items.interactions.wired.WiredSettings;
import com.eu.habbo.habbohotel.items.interactions.wired.chest.ChestNotifications;
import com.eu.habbo.habbohotel.items.interactions.wired.chest.ChestStorage;
import com.eu.habbo.habbohotel.items.interactions.wired.chest.ChestTransactionLog;
import com.eu.habbo.habbohotel.items.interactions.wired.chest.InteractionWiredChest;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomUnit;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.habbohotel.users.HabboItem;
import com.eu.habbo.habbohotel.wired.WiredEffectType;
import com.eu.habbo.habbohotel.wired.core.WiredContext;
import com.eu.habbo.habbohotel.wired.core.WiredManager;
import com.eu.habbo.habbohotel.wired.core.WiredSourceUtil;
import com.eu.habbo.habbohotel.wired.protocol.WiredOfficialSources;
import com.eu.habbo.habbohotel.wired.protocol.WiredOfficialVariableIds;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.incoming.wired.WiredSaveException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Give Currency From Chest (furni classname {@code wf_act_give_currency}). Dispenses up to
 * {@code amount} currency from a selected {@link InteractionWiredChest} to the resolved user(s),
 * decrementing and persisting the chest pool. If the chest is empty nothing is given. Safe: it only
 * grants what the chest actually holds (no minting beyond the configured pool).
 *
 * <p>Official layout (45): the shared {@link WiredChestReward} ints with the earnings category (11
 * games, 13 agency; stored) as the sixth; furni slot 0 the chests, user slot 0 the rewarded users.
 */
public class WiredEffectGiveCurrencyFromChest extends InteractionWiredEffect
        implements WiredOfficialSources, WiredOfficialVariableIds {
    static final int EARNINGS_GAMES = 11;
    static final int EARNINGS_AGENCY = 13;

    public static final WiredEffectType type = WiredEffectType.GIVE_CURRENCY_FROM_CHEST;

    private final List<Integer> chestIds = new ArrayList<>();
    private final WiredChestReward reward = new WiredChestReward();
    private int earningsCategory = EARNINGS_GAMES;
    private int userSource = WiredSourceUtil.SOURCE_TRIGGER;

    public WiredEffectGiveCurrencyFromChest(ResultSet set, Item baseItem) throws SQLException {
        super(set, baseItem);
    }

    public WiredEffectGiveCurrencyFromChest(
            int id, int userId, Item item, String extradata, int limitedStack, int limitedSells) {
        super(id, userId, item, extradata, limitedStack, limitedSells);
    }

    @Override
    public void execute(WiredContext ctx) {
        Room room = ctx.room();
        if (room == null) return;
        Integer resolved = this.reward.resolveAmount(ctx, room, 1);
        if (resolved == null || resolved <= 0) return;
        int perUser = resolved;

        InteractionWiredChest chest = this.resolveChest(room);
        if (chest == null) return;

        for (RoomUnit unit : WiredSourceUtil.resolveUsers(ctx, this.userSource)) {
            Habbo habbo = room.getHabbo(unit);
            if (habbo == null) continue;

            ChestStorage contents = chest.getContents();
            int total = 0;
            // Take from the first currency entry that still has stock; "everything" empties them all.
            for (ChestStorage.Entry entry : new ArrayList<>(contents.entries())) {
                if (entry.kind != ChestStorage.KIND_CURRENCY) continue;

                int given = contents.take(ChestStorage.KIND_CURRENCY, entry.type, perUser);
                if (given > 0) {
                    grant(habbo, entry.type, given);
                    ChestTransactionLog.record(
                            room.getId(),
                            chest.getId(),
                            ChestStorage.KIND_CURRENCY,
                            ChestTransactionLog.TYPE_WITHDRAW,
                            ChestTransactionLog.SOURCE_WIRED,
                            habbo,
                            entry.type,
                            given,
                            0,
                            null);
                    chest.persistContents(room);
                    ChestNotifications.wired(chest, room, given);
                    total += given;
                }
                if (this.reward.mode != WiredChestReward.MODE_EVERYTHING) break;
            }
            this.reward.showPopup(habbo, total);
        }
    }

    private InteractionWiredChest resolveChest(Room room) {
        for (Integer id : this.chestIds) {
            HabboItem item = room.getHabboItem(id);
            // Wired only reaches a chest whose owner upgraded it to answer wired.
            if (item instanceof InteractionWiredChest chest && chest.answersWired()) {
                return chest;
            }
        }
        return null;
    }

    private static void grant(Habbo habbo, int currencyType, int amount) {
        if (currencyType < 0) {
            habbo.giveCredits(amount);
        } else {
            habbo.givePoints(currencyType, amount);
        }
    }

    @Deprecated
    @Override
    public boolean execute(RoomUnit roomUnit, Room room, Object[] stuff) {
        return false;
    }

    @Override
    public boolean saveData(WiredSettings settings, GameClient gameClient) throws WiredSaveException {
        this.earningsCategory = normalizeCategory(this.reward.read(settings, 1, EARNINGS_GAMES));
        this.userSource = settings.userSource(0, WiredSourceUtil.SOURCE_TRIGGER);

        this.chestIds.clear();
        if (settings.getFurniIds() != null) {
            for (int id : settings.getFurniIds()) {
                this.chestIds.add(id);
            }
        }

        this.setDelay(settings.getDelay());
        return true;
    }

    @Override
    public WiredEffectType getType() {
        return type;
    }

    @Override
    public String getWiredData() {
        JsonData data = new JsonData(this.reward.amount, this.userSource, this.getDelay(), this.chestIds);
        this.reward.writeTo(data);
        data.earningsCategory = this.earningsCategory;
        return WiredManager.getGson().toJson(data);
    }

    @Override
    public void loadWiredData(ResultSet set, Room room) throws SQLException {
        this.onPickUp();

        String wiredData = set.getString("wired_data");
        if (wiredData == null || !wiredData.startsWith("{")) return;

        JsonData data = WiredManager.getGson().fromJson(wiredData, JsonData.class);
        if (data == null) return;

        // A box saved before the official layout gives its amount; 0 gave nothing and still does.
        this.reward.amount = Math.max(0, data.amount);
        this.reward.readFrom(data);
        this.earningsCategory = normalizeCategory(data.earningsCategory);
        this.userSource = data.userSource;
        this.setDelay(data.delay);
        if (data.chestIds != null) {
            this.chestIds.addAll(data.chestIds);
        }
    }

    @Override
    public void serializeWiredData(ServerMessage message, Room room) {
        message.appendBoolean(false);
        message.appendInt(WiredManager.MAXIMUM_FURNI_SELECTION);
        message.appendInt(this.chestIds.size());
        for (Integer id : this.chestIds) {
            message.appendInt(id);
        }
        message.appendInt(this.getBaseItem().getSpriteId());
        message.appendInt(this.getId());
        message.appendString(this.reward.popupText);
        int[] params = this.reward.officialParams(this.earningsCategory);
        message.appendInt(params.length);
        for (int param : params) message.appendInt(param);
        message.appendInt(0);
        message.appendInt(this.getType().code);
        message.appendInt(this.getDelay());
        message.appendInt(0);
    }

    @Override
    public void onPickUp() {
        this.chestIds.clear();
        this.reward.reset(1);
        this.earningsCategory = EARNINGS_GAMES;
        this.userSource = WiredSourceUtil.SOURCE_TRIGGER;
        this.setDelay(0);
    }

    @Override
    public boolean requiresTriggeringUser() {
        return this.userSource == WiredSourceUtil.SOURCE_TRIGGER;
    }

    @Override
    public int[] officialFurniSources() {
        return new int[] {WiredSourceUtil.SOURCE_SELECTED};
    }

    @Override
    public int[] officialUserSources() {
        return new int[] {this.userSource};
    }

    @Override
    public String[] officialVariableIds() {
        return this.reward.officialVariableIds();
    }

    private static int normalizeCategory(int category) {
        return category == EARNINGS_AGENCY ? EARNINGS_AGENCY : EARNINGS_GAMES;
    }

    static class JsonData extends WiredChestReward.Stored {
        int earningsCategory;
        int amount;
        int userSource;
        int delay;
        List<Integer> chestIds;

        public JsonData(int amount, int userSource, int delay, List<Integer> chestIds) {
            this.amount = amount;
            this.userSource = userSource;
            this.delay = delay;
            this.chestIds = chestIds;
        }
    }
}
