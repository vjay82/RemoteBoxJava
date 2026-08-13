package com.remoteboxjava;

import java.util.List;
import java.util.Objects;

/**
 * Metadata for a VirtualBox hard-disk, optical, or floppy medium.
 */
public record VirtualMedia(
        String id,
        String location,
        Type type,
        String format,
        long capacityMb,
        List<String> attachments
) {
    public enum Type {
        HARD_DISK,
        OPTICAL,
        FLOPPY
    }

    public VirtualMedia {
        id = Objects.requireNonNullElse(id, "").trim();
        location = Objects.requireNonNullElse(location, "").trim();
        type = type == null ? Type.HARD_DISK : type;
        format = Objects.requireNonNullElse(format, "").trim();
        capacityMb = Math.max(0, capacityMb);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public String displayName() {
        return location.isBlank() ? id : location;
    }
}
