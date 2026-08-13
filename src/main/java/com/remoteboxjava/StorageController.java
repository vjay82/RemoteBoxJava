package com.remoteboxjava;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A storage controller of a guest together with the media attached to it.
 *
 * @param useHostIoCache {@code null} when the transport cannot report the value
 */
public record StorageController(String name, String bus, String controllerType, int portCount,
                                Boolean useHostIoCache, boolean bootable, List<StorageAttachment> attachments) {
    public StorageController {
        name = Objects.requireNonNullElse(name, "").trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("A storage controller name is required.");
        }
        bus = StorageControllerSpec.normalizeBus(bus);
        controllerType = Objects.requireNonNullElse(controllerType, "").trim();
        if (portCount < 0) {
            throw new IllegalArgumentException("Storage port count cannot be negative.");
        }
        attachments = List.copyOf(Objects.requireNonNullElse(attachments, List.of()));
    }

    public String busDescription() {
        return switch (bus) {
            case "ide" -> "IDE";
            case "sata" -> "SATA";
            case "scsi" -> "SCSI";
            case "sas" -> "SAS";
            case "pcie" -> "PCIe";
            case "floppy" -> "Floppy";
            case "usb" -> "USB";
            case "virtio" -> "VirtIO";
            default -> bus;
        };
    }

    public int maxPortCount() {
        return switch (bus) {
            case "ide" -> 2;
            case "sata" -> 30;
            case "scsi" -> 16;
            case "sas" -> 8;
            case "floppy" -> 1;
            default -> 30;
        };
    }

    /** Hardware variants VirtualBox can emulate for the given bus. */
    public static String[] controllerTypesFor(String bus) {
        return switch (bus == null ? "" : bus.toLowerCase(Locale.ROOT)) {
            case "ide" -> new String[]{"PIIX3", "PIIX4", "ICH6"};
            case "sata" -> new String[]{"IntelAhci"};
            case "scsi" -> new String[]{"LsiLogic", "BusLogic"};
            case "sas" -> new String[]{"LSILogicSAS"};
            case "floppy" -> new String[]{"I82078"};
            case "usb" -> new String[]{"USB"};
            case "pcie" -> new String[]{"NVMe"};
            case "virtio" -> new String[]{"VirtIO"};
            default -> new String[0];
        };
    }

    /**
     * Derives the bus from the hardware variant, which is all that
     * {@code VBoxManage showvminfo --machinereadable} reports.
     */
    public static String busForControllerType(String controllerType) {
        return switch (controllerType == null ? "" : controllerType.trim().toLowerCase(Locale.ROOT)) {
            case "piix3", "piix4", "ich6" -> "ide";
            case "lsilogic", "buslogic" -> "scsi";
            case "lsilogicsas" -> "sas";
            case "i82078" -> "floppy";
            case "usb" -> "usb";
            case "nvme" -> "pcie";
            case "virtioscsi", "virtio" -> "virtio";
            default -> "sata";
        };
    }
}
