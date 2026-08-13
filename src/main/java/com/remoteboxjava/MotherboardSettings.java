package com.remoteboxjava;

import java.util.List;
import java.util.Locale;

/**
 * Configurable VirtualBox motherboard and processor-execution settings.
 */
public record MotherboardSettings(
        String bootOrder,
        String chipset,
        String pointingDevice,
        String firmware,
        boolean efiEnabled,
        boolean rtcUsesUtc,
        int executionCap,
        boolean paeEnabled,
        boolean hardwareVirtualizationEnabled,
        boolean nestedPagingEnabled
) {
    private static final List<String> CHIPSETS = List.of("piix3", "ich9");
    private static final List<String> POINTING_DEVICES = List.of(
            "ps2mouse", "usbtablet", "usbmultitouch", "usbmouse");
    private static final List<String> FIRMWARES = List.of("bios", "efi", "efi32", "efi64", "efidual");

    public MotherboardSettings {
        bootOrder = normalizeBootOrder(bootOrder);
        chipset = requiredValue(chipset, CHIPSETS, "chipset");
        pointingDevice = requiredValue(pointingDevice, POINTING_DEVICES, "pointing device");
        firmware = requiredValue(firmware, FIRMWARES, "firmware");
        if (executionCap < 1 || executionCap > 100) {
            throw new IllegalArgumentException("Processor execution cap must be between 1 and 100 percent.");
        }
    }

    public static MotherboardSettings defaults() {
        return new MotherboardSettings("disk,dvd,none,none", "piix3", "ps2mouse", "bios",
                false, false, 100, true, true, true);
    }

    private static String normalizeBootOrder(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String[] devices = normalized.split(",", -1);
        if (devices.length != 4) {
            throw new IllegalArgumentException("Boot order must contain exactly four comma-separated devices.");
        }
        for (String device : devices) {
            if (!List.of("none", "floppy", "dvd", "disk", "net").contains(device)) {
                throw new IllegalArgumentException("Unsupported boot device: " + device);
            }
        }
        return String.join(",", devices);
    }

    private static String requiredValue(String value, List<String> allowed, String label) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported " + label + ": " + value);
        }
        return normalized;
    }
}
