package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * Basic UART configuration for one of VirtualBox's two serial ports.
 */
public record SerialPortSettings(boolean enabled, String ioBase, int irq, String mode) {
    public SerialPortSettings {
        ioBase = normalizeIoBase(ioBase);
        if (irq < 0 || irq > 15) {
            throw new IllegalArgumentException("Serial IRQ must be between 0 and 15.");
        }
        mode = normalizeMode(mode);
    }

    public static SerialPortSettings com1() {
        return new SerialPortSettings(true, "0x3f8", 4, "disconnected");
    }

    private static String normalizeIoBase(String value) {
        String normalized = Objects.requireNonNullElse(value, "0x3f8").trim().toLowerCase(Locale.ROOT);
        try {
            int address = Integer.decode(normalized);
            return switch (address) {
                case 0x3f8 -> "0x3f8";
                case 0x2f8 -> "0x2f8";
                case 0x3e8 -> "0x3e8";
                case 0x2e8 -> "0x2e8";
                default -> throw new IllegalArgumentException();
            };
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Use a standard serial I/O address (0x3F8, 0x2F8, 0x3E8, or 0x2E8).");
        }
    }

    private static String normalizeMode(String value) {
        String normalized = Objects.requireNonNullElse(value, "disconnected").trim().toLowerCase(Locale.ROOT);
        if (!"disconnected".equals(normalized)) {
            throw new IllegalArgumentException("Only disconnected serial mode is currently supported.");
        }
        return normalized;
    }
}
