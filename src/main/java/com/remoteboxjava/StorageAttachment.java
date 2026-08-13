package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * A medium occupying one port/device slot of a VirtualBox storage controller.
 */
public record StorageAttachment(int port, int device, String deviceType, String medium) {
    public StorageAttachment {
        if (port < 0 || device < 0) {
            throw new IllegalArgumentException("Storage port and device numbers must be zero or greater.");
        }
        deviceType = normalizeDeviceType(deviceType);
        medium = Objects.requireNonNullElse(medium, "").trim();
    }

    public boolean removable() {
        return "dvd".equals(deviceType) || "fdd".equals(deviceType);
    }

    public boolean empty() {
        return medium.isBlank()
                || "emptydrive".equalsIgnoreCase(medium)
                || "none".equalsIgnoreCase(medium);
    }

    public String displayName() {
        if (empty()) {
            return switch (deviceType) {
                case "dvd" -> "Empty optical drive";
                case "fdd" -> "Empty floppy drive";
                default -> "Empty slot";
            };
        }
        int separator = Math.max(medium.lastIndexOf('/'), medium.lastIndexOf('\\'));
        return separator < 0 ? medium : medium.substring(separator + 1);
    }

    public String slot() {
        return "Port " + port + ", Device " + device;
    }

    public String deviceDescription() {
        return switch (deviceType) {
            case "dvd" -> "Optical drive";
            case "fdd" -> "Floppy drive";
            default -> "Hard disk";
        };
    }

    private static String normalizeDeviceType(String value) {
        String normalized = Objects.requireNonNullElse(value, "hdd").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "hdd", "harddisk", "disk" -> "hdd";
            case "dvd", "dvddrive" -> "dvd";
            case "fdd", "floppy" -> "fdd";
            default -> throw new IllegalArgumentException("Unsupported storage device type: " + value);
        };
    }
}
