package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * A persistent USB device filter attached to a virtual machine.
 *
 * <p>Blank vendor and product identifiers match any value. When supplied,
 * identifiers are four hexadecimal digits, as used by VirtualBox.</p>
 */
public record UsbDeviceFilter(String name, boolean active, String vendorId, String productId) {
    public UsbDeviceFilter {
        name = requireName(name);
        vendorId = normalizeHexIdentifier("Vendor ID", vendorId);
        productId = normalizeHexIdentifier("Product ID", productId);
    }

    private static String requireName(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("USB filter name is required.");
        }
        return normalized;
    }

    private static String normalizeHexIdentifier(String label, String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2);
        }
        if (!normalized.isBlank() && !normalized.matches("[0-9a-f]{4}")) {
            throw new IllegalArgumentException(label + " must be blank or a four-digit hexadecimal value.");
        }
        return normalized;
    }
}
