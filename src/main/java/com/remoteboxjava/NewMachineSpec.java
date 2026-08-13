package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * Configuration for creating a VirtualBox guest.
 *
 * <p>A guest can use a newly-created disk, an existing disk, or no disk. The
 * expanded fields preserve the original RemoteBox creation defaults while also
 * representing the options available in VirtualBox's expert creation flow.</p>
 */
public record NewMachineSpec(
        String name,
        String osType,
        int memoryMb,
        int cpuCount,
        int diskSizeMb,
        String diskFormat,
        boolean fixedDisk,
        String installerIso,
        boolean vrdeEnabled,
        String vrdePort,
        DiskMode diskMode,
        String existingDiskPath,
        String machineFolder,
        String controllerName,
        String controllerBus,
        int diskPort,
        int diskDevice
) {
    public enum DiskMode {
        NEW,
        EXISTING,
        NONE
    }

    /**
     * Backwards-compatible constructor for the original bootable-guest flow.
     */
    public NewMachineSpec(String name, String osType, int memoryMb, int cpuCount,
                          int diskSizeMb, String diskFormat, boolean fixedDisk,
                          String installerIso, boolean vrdeEnabled, String vrdePort) {
        this(name, osType, memoryMb, cpuCount, diskSizeMb, diskFormat, fixedDisk,
                installerIso, vrdeEnabled, vrdePort, DiskMode.NEW, "", "",
                "SATA", "sata", 0, 0);
    }

    public NewMachineSpec {
        name = requireNonBlank(name, "Guest name");
        osType = requireNonBlank(osType, "Operating-system type");
        diskFormat = normalizeDiskFormat(diskFormat);
        installerIso = normalize(installerIso);
        vrdePort = normalize(vrdePort);
        diskMode = diskMode == null ? DiskMode.NEW : diskMode;
        existingDiskPath = normalize(existingDiskPath);
        machineFolder = normalize(machineFolder);
        controllerName = requireNonBlank(controllerName, "Storage controller name");
        controllerBus = normalizeControllerBus(controllerBus);

        if (memoryMb < 4) {
            throw new IllegalArgumentException("Guest memory must be at least 4 MB.");
        }
        if (cpuCount < 1) {
            throw new IllegalArgumentException("Guest processor count must be at least one.");
        }
        if (diskMode == DiskMode.NEW && diskSizeMb < 4) {
            throw new IllegalArgumentException("Startup disk size must be at least 4 MB.");
        }
        if (diskMode == DiskMode.EXISTING && existingDiskPath.isBlank()) {
            throw new IllegalArgumentException("An existing virtual disk path is required.");
        }
        if (diskPort < 0 || diskDevice < 0) {
            throw new IllegalArgumentException("Disk port and device must be zero or greater.");
        }
    }

    public static NewMachineSpec basic(String name, String osType, int memoryMb, int cpuCount) {
        return new NewMachineSpec(name, osType, memoryMb, cpuCount, 20_480,
                "VDI", false, "", true, "3389");
    }

    private static String requireNonBlank(String value, String label) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static String normalizeDiskFormat(String value) {
        String normalized = Objects.requireNonNullElse(value, "VDI").trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "VDI", "VHD", "VMDK" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported virtual disk format: " + value);
        };
    }

    private static String normalizeControllerBus(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ide", "sata", "scsi", "sas", "pcie" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported storage controller bus: " + value);
        };
    }
}
