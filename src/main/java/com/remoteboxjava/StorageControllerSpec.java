package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * Definition of a VirtualBox storage controller that can be added to a guest.
 */
public record StorageControllerSpec(String name, String bus) {
    public StorageControllerSpec {
        name = Objects.requireNonNullElse(name, "").trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("A storage controller name is required.");
        }
        if (name.contains("\"")) {
            throw new IllegalArgumentException("Storage controller names cannot contain quotation marks.");
        }
        bus = normalizeBus(bus);
    }

    static String normalizeBus(String value) {
        String normalized = Objects.requireNonNullElse(value, "sata").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ide", "sata", "scsi", "sas", "pcie", "floppy", "usb", "virtio" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported storage controller type: " + value);
        };
    }
}
