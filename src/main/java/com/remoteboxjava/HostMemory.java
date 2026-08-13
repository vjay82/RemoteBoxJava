package com.remoteboxjava;

import java.util.Locale;

/**
 * Physical memory of the VirtualBox host, in megabytes.
 */
public record HostMemory(long totalMb, long availableMb) {
    public HostMemory {
        if (totalMb < 0 || availableMb < 0) {
            throw new IllegalArgumentException("Host memory sizes cannot be negative.");
        }
        availableMb = Math.min(availableMb, totalMb);
    }

    public long usedMb() {
        return totalMb - availableMb;
    }

    public int usedPercent() {
        return totalMb == 0 ? 0 : (int) Math.round(100.0 * usedMb() / totalMb);
    }

    public String describe() {
        return "Server Memory: %s of %s used (%s free)"
                .formatted(gigabytes(usedMb()), gigabytes(totalMb), gigabytes(availableMb));
    }

    public String describeExactly() {
        return "%d MB used, %d MB free, %d MB total".formatted(usedMb(), availableMb, totalMb);
    }

    private static String gigabytes(long megabytes) {
        return String.format(Locale.ROOT, "%.1f GB", megabytes / 1024.0);
    }
}
