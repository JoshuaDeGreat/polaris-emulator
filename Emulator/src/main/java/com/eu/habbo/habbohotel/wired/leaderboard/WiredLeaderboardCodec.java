package com.eu.habbo.habbohotel.wired.leaderboard;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The official AIR storage format of the variable leaderboard box (VariableLeaderboardConfigCodec):
 * 19 int params, 12 variable ids and 17 TAB-separated text fields, with the same validation, so a
 * box saved by the AIR client and one saved by Octane read back the same.
 *
 * <p>Our save packet has no variable-id list, so on the wire the 12 variable ids ride after the
 * 17 text fields ({@link #fromWire}/{@link #toWire}); storage keeps the official three parts.
 */
public final class WiredLeaderboardCodec {

    public static final int COLUMN_UNUSED = -1;
    public static final int COLUMN_USERNAME = 0;
    public static final int COLUMN_VALUE = 1;
    public static final int COLUMN_LAST_UPDATE = 2;
    public static final int COLUMN_CREATION = 3;
    public static final int COLUMN_LEVEL = 4;
    public static final int COLUMN_TEXT_CONNECTION = 5;

    public static final int SORT_HIGHEST_VALUE = 0;
    public static final int SORT_LOWEST_VALUE = 1;
    public static final int SORT_OLDEST_CREATION = 2;
    public static final int SORT_LATEST_CREATION = 3;
    public static final int SORT_OLDEST_UPDATE = 4;
    public static final int SORT_LATEST_UPDATE = 5;

    public static final int OPERAND_LITERAL = 0;
    public static final int OPERAND_GLOBAL_VARIABLE = 1;

    static final int[] PAGE_SIZES = {10, 15, 20};
    public static final int INT_PARAM_COUNT = 19;
    public static final int VARIABLE_ID_COUNT = 12;
    public static final int STRING_FIELD_COUNT = 17;
    public static final int MAX_COLUMNS = 4;
    public static final int MAX_OVERRIDES = 10;
    public static final int MIN_OFFSET_X = -32;
    public static final int MAX_OFFSET_X = 32;
    public static final String NULL_VARIABLE_ID = "0";

    static final int MAX_TITLE = 50;
    static final int MAX_DESCRIPTION = 80;
    static final int MIN_LABEL = 2;
    static final int MAX_LABEL = 12;
    static final int MAX_OVERRIDE_NAME = 22;
    static final int MAX_INT_PARAMS_TEXT = 400;
    static final int MAX_VARIABLE_IDS_TEXT = 240;
    static final int MAX_STRING_PARAM_BYTES = 1000;

    private static final String SEPARATOR = "\t";
    private static final String[] DEFAULT_LABELS = {"Username", "Value", "Last update", "Creation", "Level", "Text"};
    private static final int[] DEFAULT_WIDTHS = {3, 0, 1, 1, 0, 2};
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** The three stored parts, as the AIR client writes them. */
    public record Stored(int[] intParams, String[] variableIds, String stringParam) {}

    /** A decoded config, or why it is not one ({@code wiredfurni.error.variable_leaderboard.*}). */
    public record Decoded(WiredLeaderboardConfig config, String error) {
        public boolean valid() {
            return this.config != null;
        }

        static Decoded invalid() {
            return new Decoded(null, "invalid_configuration");
        }
    }

    private WiredLeaderboardCodec() {}

    /** A box that was never configured: Username + Value, highest value first, 10 per page. */
    public static WiredLeaderboardConfig defaults(int tileX, int tileY) {
        return new WiredLeaderboardConfig(
                "",
                "",
                SORT_HIGHEST_VALUE,
                0,
                List.of(defaultColumn(COLUMN_USERNAME), defaultColumn(COLUMN_VALUE)),
                false,
                false,
                OPERAND_LITERAL,
                NULL_VARIABLE_ID,
                NULL_VARIABLE_ID,
                "",
                new TreeMap<>(),
                tileX,
                tileY,
                0,
                0);
    }

    public static WiredLeaderboardConfig.Column defaultColumn(int type) {
        return new WiredLeaderboardConfig.Column(type, DEFAULT_LABELS[type], DEFAULT_WIDTHS[type]);
    }

    /** Header label of a column type when the box does not name it. */
    public static String defaultLabel(int type) {
        return type >= 0 && type < DEFAULT_LABELS.length ? DEFAULT_LABELS[type] : "";
    }

    /** Column width ids 0..3 are 70, 90, 120 and 170 pixels. */
    public static boolean isValidWidth(int width) {
        return width >= 0 && width <= 3;
    }

    // ---- our wire ----------------------------------------------------------------------------

    /**
     * Our save packet: the official int params, and a string param holding the 17 text fields
     * followed by the 12 variable ids (29 TAB fields), or "" for a box that is not configured.
     */
    public static Stored fromWire(int[] intParams, String wireString) {
        String text = wireString == null ? "" : wireString;
        if (text.isEmpty()) {
            return new Stored(intParams == null ? new int[0] : intParams.clone(), new String[0], "");
        }
        String[] fields = text.split(SEPARATOR, -1);
        if (fields.length != STRING_FIELD_COUNT + VARIABLE_ID_COUNT) {
            return null;
        }
        String stringParam = String.join(SEPARATOR, Arrays.copyOfRange(fields, 0, STRING_FIELD_COUNT));
        String[] variableIds = Arrays.copyOfRange(fields, STRING_FIELD_COUNT, fields.length);
        return new Stored(intParams == null ? new int[0] : intParams.clone(), variableIds, stringParam);
    }

    /**
     * A save: ours carries the variable ids after the string fields; the official client sends the
     * string fields alone and the ids as the packet's variable ids.
     */
    public static Stored fromSave(int[] intParams, String wireString, String[] variableIds) {
        String text = wireString == null ? "" : wireString;
        if (!text.isEmpty()
                && variableIds != null
                && variableIds.length == VARIABLE_ID_COUNT
                && text.split(SEPARATOR, -1).length == STRING_FIELD_COUNT) {
            return new Stored(intParams == null ? new int[0] : intParams.clone(), variableIds.clone(), text);
        }
        return fromWire(intParams, wireString);
    }

    public static String toWire(Stored stored) {
        if (stored.stringParam().isEmpty()) {
            return "";
        }
        return stored.stringParam() + SEPARATOR + String.join(SEPARATOR, stored.variableIds());
    }

    // ---- decode ------------------------------------------------------------------------------

    /**
     * Reads a stored box. An empty string param is a box nobody configured yet and decodes to the
     * defaults at {@code (tileX, tileY)}; anything else must pass every official rule.
     */
    public static Decoded decode(Stored stored, int tileX, int tileY) {
        if (stored == null) {
            return Decoded.invalid();
        }
        if (stored.stringParam() == null || stored.stringParam().isEmpty()) {
            return new Decoded(defaults(tileX, tileY), null);
        }
        int[] ints = stored.intParams();
        String[] ids = stored.variableIds();
        if (ints == null || ints.length < 14 || ids == null || ids.length < VARIABLE_ID_COUNT) {
            return Decoded.invalid();
        }
        for (String id : ids) {
            if (id == null || id.isEmpty()) {
                return Decoded.invalid();
            }
        }
        String[] strings = stored.stringParam().split(SEPARATOR, -1);
        if (strings.length != STRING_FIELD_COUNT) {
            return Decoded.invalid();
        }

        int sortMode = ints[0];
        int columnCount = ints[1];
        int filter = ints[6];
        int compare = ints[7];
        int operandKind = ints[8];
        int overrideCount = ints[9];
        int pageSizeOption = ints.length > 18 ? ints[18] : 0;
        if (sortMode < SORT_HIGHEST_VALUE || sortMode > SORT_LATEST_UPDATE) return Decoded.invalid();
        if (columnCount < 1 || columnCount > MAX_COLUMNS) return Decoded.invalid();
        if ((filter != 0 && filter != 1) || (compare != 0 && compare != 1)) return Decoded.invalid();
        if (operandKind != OPERAND_LITERAL && operandKind != OPERAND_GLOBAL_VARIABLE) return Decoded.invalid();
        if (overrideCount < 0 || overrideCount > MAX_OVERRIDES) return Decoded.invalid();
        if (pageSizeOption < 0 || pageSizeOption >= PAGE_SIZES.length) return Decoded.invalid();

        List<WiredLeaderboardConfig.Column> columns = new ArrayList<>(columnCount);
        Set<Integer> types = new HashSet<>();
        for (int i = 0; i < columnCount; i++) {
            int type = ints[2 + i];
            if (type < COLUMN_USERNAME || type > COLUMN_TEXT_CONNECTION || !types.add(type)) {
                return Decoded.invalid();
            }
            int widthIndex = 14 + i;
            int width = (widthIndex < ints.length && isValidWidth(ints[widthIndex]))
                    ? ints[widthIndex]
                    : DEFAULT_WIDTHS[type];
            String label = strings[2 + i];
            if (label.length() < MIN_LABEL || label.length() > MAX_LABEL) {
                return new Decoded(null, "column_name_length");
            }
            columns.add(new WiredLeaderboardConfig.Column(type, label, width));
        }
        if (columns.get(0).type() != COLUMN_USERNAME) return Decoded.invalid();

        String title = strings[0];
        String description = strings[1];
        if (title.isEmpty()) return new Decoded(null, "title_required");
        if (title.length() > MAX_TITLE || description.length() > MAX_DESCRIPTION) return Decoded.invalid();

        String filterVariable = canonicalId(ids[0]);
        String operandVariable = canonicalId(ids[1]);
        String literal = strings[6];
        boolean filterOn = filter == 1;
        boolean compareOn = compare == 1;
        if (!filterOn) {
            if (!filterVariable.equals(NULL_VARIABLE_ID)
                    || !operandVariable.equals(NULL_VARIABLE_ID)
                    || compareOn
                    || operandKind != OPERAND_LITERAL
                    || !literal.isEmpty()) {
                return Decoded.invalid();
            }
        } else {
            if (filterVariable.equals(NULL_VARIABLE_ID)) return new Decoded(null, "filter_required");
            if (!compareOn) {
                if (!operandVariable.equals(NULL_VARIABLE_ID) || operandKind != OPERAND_LITERAL || !literal.isEmpty()) {
                    return Decoded.invalid();
                }
            } else if (operandKind == OPERAND_LITERAL) {
                if (!operandVariable.equals(NULL_VARIABLE_ID) || !isCanonicalLong(literal)) {
                    return Decoded.invalid();
                }
            } else {
                if (operandVariable.equals(NULL_VARIABLE_ID)) return new Decoded(null, "global_operand_required");
                if (!literal.isEmpty()) return Decoded.invalid();
            }
        }

        TreeMap<String, String> overrides = new TreeMap<>();
        for (int i = 0; i < MAX_OVERRIDES; i++) {
            String id = canonicalId(ids[2 + i]);
            String name = strings[7 + i];
            if (i < overrideCount) {
                if (id.equals(NULL_VARIABLE_ID)
                        || name.isEmpty()
                        || name.length() > MAX_OVERRIDE_NAME
                        || overrides.put(id, name) != null) {
                    return Decoded.invalid();
                }
            }
        }

        int offsetX = ints[12];
        if (offsetX < MIN_OFFSET_X || offsetX > MAX_OFFSET_X) return Decoded.invalid();

        WiredLeaderboardConfig config = new WiredLeaderboardConfig(
                title,
                description,
                sortMode,
                pageSizeOption,
                columns,
                filterOn,
                compareOn,
                operandKind,
                filterVariable,
                operandVariable,
                literal,
                overrides,
                ints[10],
                ints[11],
                offsetX,
                ints[13]);
        Stored canonical = encode(config);
        if (!fitsStorage(canonical)) {
            return Decoded.invalid();
        }
        return new Decoded(config, null);
    }

    // ---- encode ------------------------------------------------------------------------------

    public static Stored encode(WiredLeaderboardConfig config) {
        int[] ints = new int[INT_PARAM_COUNT];
        String[] ids = new String[VARIABLE_ID_COUNT];
        String[] strings = new String[STRING_FIELD_COUNT];
        Arrays.fill(ids, NULL_VARIABLE_ID);
        Arrays.fill(strings, "");

        List<WiredLeaderboardConfig.Column> columns = config.columns();
        ints[0] = config.sortMode();
        ints[1] = columns.size();
        for (int i = 0; i < MAX_COLUMNS; i++) {
            boolean active = i < columns.size();
            ints[2 + i] = active ? columns.get(i).type() : COLUMN_UNUSED;
            ints[14 + i] = active ? columns.get(i).width() : 0;
            strings[2 + i] = active ? columns.get(i).label() : "";
        }
        ints[6] = config.filterEnabled() ? 1 : 0;
        ints[7] = config.compareEnabled() ? 1 : 0;
        ints[8] = config.operandKind();
        ints[9] = config.displayOverrides().size();
        ints[10] = config.tileX();
        ints[11] = config.tileY();
        ints[12] = config.offsetX();
        ints[13] = config.altitude();
        ints[18] = config.pageSizeOption();

        ids[0] = config.filterVariableId();
        ids[1] = config.operandVariableId();
        int index = 0;
        for (Map.Entry<String, String> override : config.displayOverrides().entrySet()) {
            ids[2 + index] = override.getKey();
            strings[7 + index] = override.getValue();
            index++;
        }
        strings[0] = config.title();
        strings[1] = config.description();
        strings[6] = config.comparisonLiteral();
        return new Stored(ints, ids, String.join(SEPARATOR, strings));
    }

    static boolean fitsStorage(Stored stored) {
        String ints = Arrays.stream(stored.intParams())
                .mapToObj(Integer::toString)
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
        return ints.length() <= MAX_INT_PARAMS_TEXT
                && String.join("|", stored.variableIds()).length() <= MAX_VARIABLE_IDS_TEXT
                && stored.stringParam().getBytes(StandardCharsets.UTF_8).length <= MAX_STRING_PARAM_BYTES;
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** "0" and "n" both mean "no variable". */
    public static String canonicalId(String id) {
        return (id == null || id.isEmpty() || id.equals("n")) ? NULL_VARIABLE_ID : id;
    }

    /** A signed 64-bit decimal without leading zeros or "-0". */
    static boolean isCanonicalLong(String text) {
        if (text == null || text.isEmpty() || !text.matches("-?(0|[1-9][0-9]*)") || text.equals("-0")) {
            return false;
        }
        BigInteger value = new BigInteger(text);
        return value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0;
    }

    /** The client's VariableLeaderboardDisplayNameFormatter.suggest: "coins_won" -> "Coins Won". */
    public static String suggest(String technicalName) {
        if (technicalName == null || technicalName.isEmpty()) {
            return "Variable";
        }
        String spaced = technicalName.replaceAll("[_-]+", " ").trim();
        List<String> words = new ArrayList<>();
        for (String word : spaced.split("\\s+")) {
            if (word.isEmpty()) continue;
            String lower = word.toLowerCase(Locale.ROOT);
            words.add(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
        }
        return words.isEmpty() ? "Variable" : String.join(" ", words);
    }

    public static int[] pageSizes() {
        return PAGE_SIZES.clone();
    }
}
