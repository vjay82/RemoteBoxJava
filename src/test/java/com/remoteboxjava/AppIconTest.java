package com.remoteboxjava;

import org.junit.jupiter.api.Test;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppIconTest {
    @Test
    void windowIconsCoverTheUsualTaskbarSizes() {
        List<Image> icons = AppIcon.windowIcons();

        assertTrue(icons.size() >= 5);
        assertEquals(16, icons.get(0).getWidth(null));
        assertEquals(256, icons.get(icons.size() - 1).getWidth(null));
    }

    @Test
    void everySizeIsSquareAndPainted() {
        for (int size : new int[]{16, 24, 32, 48, 128, 256}) {
            BufferedImage icon = AppIcon.render(size);

            assertEquals(size, icon.getWidth());
            assertEquals(size, icon.getHeight());
            // The badge covers the centre, so it must be fully opaque there.
            assertEquals(255, icon.getRGB(size / 2, size / 2) >>> 24, "transparent centre at " + size);
            assertTrue(distinctColours(icon) > 2, "flat icon at " + size);
        }
    }

    @Test
    void cornersStayTransparentForTheRoundedBadge() {
        BufferedImage icon = AppIcon.render(256);

        assertEquals(0, icon.getRGB(0, 0) >>> 24);
        assertEquals(0, icon.getRGB(255, 255) >>> 24);
    }

    private static int distinctColours(BufferedImage icon) {
        return (int) java.util.stream.IntStream.range(0, icon.getWidth())
                .boxed()
                .flatMap(x -> java.util.stream.IntStream.range(0, icon.getHeight()).mapToObj(y -> icon.getRGB(x, y)))
                .distinct()
                .count();
    }
}
