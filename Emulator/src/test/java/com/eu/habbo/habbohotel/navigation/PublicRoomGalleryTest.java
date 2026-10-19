package com.eu.habbo.habbohotel.navigation;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PublicRoomGalleryTest {
    @Test
    void acceptsOnlyOwnImagePaths() {
        assertTrue(PublicRoomGallery.validImagePath(""));
        assertTrue(PublicRoomGallery.validImagePath(PublicRoomGallery.BASE + "/images/12345678-1234-1234-1234-123456789abc.png"));
        assertFalse(PublicRoomGallery.validImagePath(PublicRoomGallery.BASE + "/images/../../secret.png"));
        assertFalse(PublicRoomGallery.validImagePath("javascript:alert(1)"));
        assertFalse(PublicRoomGallery.validImagePath("https://example.com/picture.png"));
    }

    @Test
    void cropsTheCentreAndWritesAnActualSquarePng() throws Exception {
        BufferedImage source = new BufferedImage(512, 256, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(Color.RED); graphics.fillRect(0, 0, 512, 256);
        graphics.setColor(Color.BLUE); graphics.fillRect(128, 0, 256, 256);
        graphics.dispose();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(source, "png", input);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(PublicRoomGallery.normalizeImage(input.toByteArray())));
        assertEquals(256, result.getWidth());
        assertEquals(256, result.getHeight());
        assertEquals(Color.BLUE.getRGB(), result.getRGB(0, 0));
        assertEquals(Color.BLUE.getRGB(), result.getRGB(255, 255));
    }

    @Test
    void rejectsInvalidAndOversizeUploads() {
        assertThrows(IllegalArgumentException.class, () -> PublicRoomGallery.normalizeImage(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> PublicRoomGallery.normalizeImage("<svg/>".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> PublicRoomGallery.normalizeImage(new byte[PublicRoomGallery.MAX_IMAGE_BYTES + 1]));
    }

    @Test
    void rejectsLargeDimensionsBeforeDecodingPixels() throws Exception {
        BufferedImage source = new BufferedImage(2049, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(source, "png", input);
        assertThrows(IllegalArgumentException.class, () -> PublicRoomGallery.normalizeImage(input.toByteArray()));
    }
}
