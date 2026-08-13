package com.remoteboxjava;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable summary of a VirtualBox guest obtained from VBoxManage.
 */
public record VirtualMachine(
        String name,
        String id,
        String state,
        String osType,
        int memoryMb,
        int cpuCount,
        String groups,
        String description,
        String vrdePort
) {
    public static VirtualMachine fromInfo(String name, String id, Map<String, String> info) {
        return new VirtualMachine(
                name,
                id,
                info.getOrDefault("VMState", "unknown"),
                info.getOrDefault("ostype", "Unknown"),
                parseInt(info.get("memory"), 0),
                parseInt(info.get("cpus"), 1),
                info.getOrDefault("groups", "/"),
                info.getOrDefault("description", ""),
                info.getOrDefault("vrdeport", "")
        );
    }

    public boolean isRunning() {
        return "running".equals(normalizedState());
    }

    public boolean isPaused() {
        return "paused".equals(normalizedState());
    }

    public boolean isSaved() {
        return "saved".equals(normalizedState());
    }

    public boolean isPoweredOff() {
        String normalized = normalizedState();
        return "poweroff".equals(normalized)
                || "poweredoff".equals(normalized)
                || "aborted".equals(normalized);
    }

    public boolean canStart() {
        return isPoweredOff() || isSaved();
    }

    public boolean canStop() {
        return isRunning() || isPaused();
    }

    public String displayState() {
        if (state == null || state.isBlank()) {
            return "Unknown";
        }
        return state.substring(0, 1).toUpperCase(Locale.ROOT) + state.substring(1).replace('_', ' ');
    }

    public String displayGroup() {
        return Objects.equals(groups, "/") || groups.isBlank() ? "Ungrouped" : groups;
    }

    private String normalizedState() {
        return state == null ? "" : state.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
