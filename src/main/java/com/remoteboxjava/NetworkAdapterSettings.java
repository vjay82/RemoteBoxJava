package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * Editable configuration for the first VirtualBox network adapter.
 */
public record NetworkAdapterSettings(boolean enabled, String attachmentType, String adapterName) {
    public NetworkAdapterSettings {
        attachmentType = normalizeAttachmentType(attachmentType);
        adapterName = Objects.requireNonNullElse(adapterName, "").trim();
        if (enabled && "bridged".equals(attachmentType) && adapterName.isBlank()) {
            throw new IllegalArgumentException("A bridged adapter requires a host interface name.");
        }
    }

    public static NetworkAdapterSettings nat() {
        return new NetworkAdapterSettings(true, "nat", "");
    }

    private static String normalizeAttachmentType(String value) {
        String normalized = Objects.requireNonNullElse(value, "nat").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "nat", "bridged", "hostonly", "intnet", "none" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported network attachment type: " + value);
        };
    }
}
