package com.eu.habbo.habbohotel.navigation;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.rooms.Room;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/** Listing membership uses navigator_publics; small per-room settings use emulator_settings.
 * Pictures live in an explicitly configured durable directory, not in the client bundle. */
public final class PublicRoomGallery {
    public static final String BASE = "/api/navigator/public-rooms";
    public static final int MAX_IMAGE_BYTES = 256 * 1024;
    private static final String PREFIX = "navigator.gallery.room.";
    private static volatile JsonObject cachedPublic;
    private static volatile long cachedAt;

    private PublicRoomGallery() {}

    public static Path imageDirectory() {
        String directory = System.getenv("PUBLIC_ROOM_IMAGE_DIR");
        return Path.of(directory == null || directory.isBlank() ? "public-room-images" : directory)
                .toAbsolutePath().normalize();
    }

    public static boolean validImagePath(String path) {
        return path != null && (path.isEmpty() || path.matches(BASE + "/images/[0-9a-f-]{36}\\.png"));
    }

    public static synchronized JsonObject list(boolean admin) throws Exception {
        if (!admin && cachedPublic != null && System.currentTimeMillis() - cachedAt < 3000) {
            return cachedPublic.deepCopy();
        }
        JsonObject result = new JsonObject();
        JsonArray categories = new JsonArray();
        List<JsonObject> rooms = new ArrayList<>();
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id,name,visible FROM navigator_publiccats ORDER BY order_num DESC,id");
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    if (!admin && !"1".equals(rows.getString("visible"))) continue;
                    JsonObject category = new JsonObject();
                    category.addProperty("id", rows.getInt("id"));
                    category.addProperty("name", rows.getString("name"));
                    category.addProperty("visible", "1".equals(rows.getString("visible")));
                    categories.add(category);
                }
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT r.id,r.name,r.description,r.users_max,r.state,
                      n.public_cat_id,n.visible,c.visible AS category_visible,s.value AS gallery_settings
                    FROM rooms r
                    LEFT JOIN navigator_publics n ON n.room_id=r.id
                    LEFT JOIN navigator_publiccats c ON c.id=n.public_cat_id
                    LEFT JOIN emulator_settings s ON s.`key`=CONCAT('navigator.gallery.room.',r.id)
                    WHERE n.room_id IS NOT NULL OR r.is_public='1'
                    ORDER BY r.id,n.public_cat_id LIMIT 500
                    """); ResultSet rows = query.executeQuery()) {
                java.util.Set<Integer> included = new java.util.HashSet<>();
                while (rows.next()) {
                    int id = rows.getInt("id");
                    boolean visible = rows.getObject("public_cat_id") == null
                            || ("1".equals(rows.getString("visible")) && "1".equals(rows.getString("category_visible")));
                    if (!admin && (!visible || "invisible".equalsIgnoreCase(rows.getString("state")))) continue;
                    if (!included.add(id)) continue;
                    JsonObject settings = new JsonObject();
                    try {
                        String value = rows.getString("gallery_settings");
                        if (value != null) settings = JsonParser.parseString(value).getAsJsonObject();
                    } catch (RuntimeException ignored) { /* Older/manual settings must not break browsing. */ }
                    JsonObject room = new JsonObject();
                    room.addProperty("roomId", id);
                    room.addProperty("name", rows.getString("name"));
                    room.addProperty("description", rows.getString("description"));
                    room.addProperty("categoryId", rows.getInt("public_cat_id"));
                    room.addProperty("visible", visible);
                    room.addProperty("maxUsers", rows.getInt("users_max"));
                    Room active = Emulator.getGameEnvironment().getRoomManager().getRoom(id);
                    room.addProperty("userCount", active == null ? 0 : active.getUserCount());
                    int order = id;
                    String image = "";
                    try {
                        if (settings.has("order")) order = settings.get("order").getAsInt();
                        if (settings.has("image")) image = settings.get("image").getAsString();
                    } catch (RuntimeException ignored) { /* Invalid manual metadata uses safe defaults. */ }
                    room.addProperty("order", order);
                    room.addProperty("image", validImagePath(image) ? image : "");
                    rooms.add(room);
                }
            }
        }
        rooms.sort(Comparator.comparingInt((JsonObject room) -> room.get("order").getAsInt())
                .thenComparingInt(room -> room.get("roomId").getAsInt()));
        JsonArray entries = new JsonArray();
        rooms.forEach(entries::add);
        result.add("categories", categories);
        result.add("rooms", entries);
        if (!admin) {
            cachedPublic = result.deepCopy();
            cachedAt = System.currentTimeMillis();
        }
        return result;
    }

    public static synchronized void save(int roomId, int categoryId, boolean visible, int order, String image) throws Exception {
        if (roomId <= 0 || categoryId <= 0 || order < 0 || order > 100000 || !validImagePath(image)) {
            throw new IllegalArgumentException("Choose a room, a public category and an order from 0 to 100000.");
        }
        JsonObject settings = new JsonObject();
        settings.addProperty("order", order);
        settings.addProperty("image", image);
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection()) {
            try (PreparedStatement check = connection.prepareStatement("SELECT id FROM navigator_publiccats WHERE id=?")) {
                check.setInt(1, categoryId);
                try (ResultSet row = check.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("Public category does not exist.");
                }
            }
            try (PreparedStatement write = connection.prepareStatement("INSERT INTO emulator_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)")) {
                write.setString(1, PREFIX + roomId);
                write.setString(2, settings.toString());
                write.executeUpdate();
            }
            boolean exists;
            try (PreparedStatement check = connection.prepareStatement("SELECT room_id FROM navigator_publics WHERE room_id=? LIMIT 1")) {
                check.setInt(1, roomId);
                try (ResultSet row = check.executeQuery()) { exists = row.next(); }
            }
            String sql = exists ? "UPDATE navigator_publics SET public_cat_id=?,visible=? WHERE room_id=?"
                    : "INSERT INTO navigator_publics (public_cat_id,visible,room_id) VALUES (?,?,?)";
            try (PreparedStatement write = connection.prepareStatement(sql)) {
                write.setInt(1, categoryId);
                write.setString(2, visible ? "1" : "0");
                write.setInt(3, roomId);
                write.executeUpdate();
            }
            try (PreparedStatement write = connection.prepareStatement("UPDATE navigator_publiccats SET image='1' WHERE id=?")) {
                write.setInt(1, categoryId);
                write.executeUpdate();
            }
        }
        cachedPublic = null;
        // Existing clients continue to receive standard navigator listings.
        Emulator.getGameEnvironment().getNavigatorManager().loadNavigator();
    }

    public static byte[] normalizeImage(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Picture must be no larger than 256 KB.");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("Use a PNG or JPEG picture.");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw new IllegalArgumentException("Use a PNG or JPEG picture.");
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 2048 || height > 2048) throw new IllegalArgumentException("Picture dimensions are too large.");
                BufferedImage source = reader.read(0);
                BufferedImage target = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = target.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    int side = Math.min(width, height), x = (width - side) / 2, y = (height - side) / 2;
                    graphics.drawImage(source, 0, 0, 256, 256, x, y, x + side, y + side, null);
                } finally { graphics.dispose(); }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(target, "png", output);
                return output.toByteArray();
            } finally { reader.dispose(); }
        }
    }

    public static String upload(byte[] bytes) throws IOException {
        byte[] normalized = normalizeImage(bytes);
        Path directory = imageDirectory();
        Files.createDirectories(directory);
        String filename = UUID.randomUUID() + ".png";
        Files.write(directory.resolve(filename), normalized, java.nio.file.StandardOpenOption.CREATE_NEW);
        return BASE + "/images/" + filename;
    }
}
