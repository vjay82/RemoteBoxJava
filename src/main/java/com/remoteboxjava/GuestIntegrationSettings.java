package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * Host/guest integration channels configured by VirtualBox.
 */
public record GuestIntegrationSettings(String clipboardMode, String dragAndDropMode) {
    public GuestIntegrationSettings {
        clipboardMode = normalize("Clipboard mode", clipboardMode);
        dragAndDropMode = normalize("Drag-and-drop mode", dragAndDropMode);
    }

    private static String normalize(String label, String value) {
        String normalized = Objects.requireNonNullElse(value, "disabled").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "disabled", "hosttoguest", "guesttohost", "bidirectional" -> normalized;
            default -> throw new IllegalArgumentException(label
                    + " must be disabled, host-to-guest, guest-to-host, or bidirectional.");
        };
    }
}
