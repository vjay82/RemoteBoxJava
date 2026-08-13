package com.remoteboxjava;

import java.util.Objects;

/**
 * A machine-level shared folder exposed from the host to a guest.
 */
public record SharedFolder(String name, String hostPath, boolean readOnly, boolean autoMount) {
    public SharedFolder {
        name = normalizeRequired(name, "Shared-folder name");
        hostPath = normalizeRequired(hostPath, "Shared-folder host path");
    }

    private static String normalizeRequired(String value, String label) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return normalized;
    }
}
