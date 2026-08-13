package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * USB controller configuration for a virtual machine.
 */
public record UsbControllerSettings(boolean enabled, String controller) {
    public UsbControllerSettings {
        controller = normalizeController(controller);
        if (!enabled) {
            controller = "none";
        }
    }

    private static String normalizeController(String value) {
        String normalized = Objects.requireNonNullElse(value, "none").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none", "ohci", "ehci", "xhci" -> normalized;
            default -> throw new IllegalArgumentException("USB controller must be none, OHCI, EHCI, or xHCI.");
        };
    }
}
