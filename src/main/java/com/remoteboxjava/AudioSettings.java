package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * Editable VirtualBox guest audio configuration.
 */
public record AudioSettings(boolean enabled, String controller, String driver) {
    public AudioSettings {
        controller = normalizeController(controller);
        driver = normalizeDriver(driver);
    }

    public static AudioSettings disabled() {
        return new AudioSettings(false, "ac97", "default");
    }

    private static String normalizeController(String value) {
        String normalized = Objects.requireNonNullElse(value, "ac97").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ac97", "hda", "sb16" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported audio controller: " + value);
        };
    }

    private static String normalizeDriver(String value) {
        String normalized = Objects.requireNonNullElse(value, "default").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "default", "none", "null", "directsound", "was", "oss", "alsa", "pulse", "coreaudio" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported audio driver: " + value);
        };
    }
}
