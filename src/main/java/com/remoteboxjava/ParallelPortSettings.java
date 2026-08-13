package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * Basic configuration for VirtualBox's parallel port.
 */
public record ParallelPortSettings(boolean enabled, String ioBase, int irq) {
    public ParallelPortSettings {
        ioBase = normalizeIoBase(ioBase);
        if (irq < 0 || irq > 15) {
            throw new IllegalArgumentException("Parallel-port IRQ must be between 0 and 15.");
        }
    }

    public static ParallelPortSettings lpt1() {
        return new ParallelPortSettings(true, "0x378", 7);
    }

    private static String normalizeIoBase(String value) {
        String normalized = Objects.requireNonNullElse(value, "0x378").trim().toLowerCase(Locale.ROOT);
        try {
            int address = Integer.decode(normalized);
            return switch (address) {
                case 0x378 -> "0x378";
                case 0x278 -> "0x278";
                case 0x3bc -> "0x3bc";
                default -> throw new IllegalArgumentException();
            };
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Use a standard parallel I/O address (0x378, 0x278, or 0x3BC).");
        }
    }
}
