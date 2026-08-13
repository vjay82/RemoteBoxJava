package com.remoteboxjava;

import java.util.List;
import java.util.Locale;

/**
 * VirtualBox graphics and recording settings independent of remote display.
 */
public record DisplaySettings(
        String graphicsController,
        int monitorCount,
        int scaleFactor,
        boolean acceleration3dEnabled,
        boolean recordingEnabled,
        String recordingFile
) {
    private static final List<String> GRAPHICS_CONTROLLERS = List.of("vboxvga", "vmsvga", "vboxsvga");

    public DisplaySettings {
        graphicsController = graphicsController == null ? "" : graphicsController.trim().toLowerCase(Locale.ROOT);
        if (!GRAPHICS_CONTROLLERS.contains(graphicsController)) {
            throw new IllegalArgumentException("Unsupported graphics controller: " + graphicsController);
        }
        if (monitorCount < 1 || monitorCount > 8) {
            throw new IllegalArgumentException("Monitor count must be between 1 and 8.");
        }
        if (scaleFactor < 100 || scaleFactor > 200) {
            throw new IllegalArgumentException("Scale factor must be between 100 and 200 percent.");
        }
        recordingFile = recordingFile == null ? "" : recordingFile.trim();
    }

    public static DisplaySettings defaults() {
        return new DisplaySettings("vmsvga", 1, 100, false, false, "");
    }
}
