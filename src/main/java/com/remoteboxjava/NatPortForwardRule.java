package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * A VirtualBox NAT port-forwarding rule for a guest network adapter.
 */
public record NatPortForwardRule(
        String name,
        String protocol,
        String hostIp,
        int hostPort,
        String guestIp,
        int guestPort
) {
    public NatPortForwardRule {
        name = requireNonBlank(name, "Rule name");
        protocol = normalizeProtocol(protocol);
        hostIp = Objects.requireNonNullElse(hostIp, "").trim();
        guestIp = Objects.requireNonNullElse(guestIp, "").trim();
        requirePort(hostPort, "Host port");
        requirePort(guestPort, "Guest port");
        if (name.contains(",")) {
            throw new IllegalArgumentException("NAT rule names cannot contain commas.");
        }
    }

    public String toVBoxManageRule() {
        return String.join(",", name, protocol, hostIp, Integer.toString(hostPort), guestIp, Integer.toString(guestPort));
    }

    private static String requireNonBlank(String value, String label) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return normalized;
    }

    private static String normalizeProtocol(String value) {
        String normalized = Objects.requireNonNullElse(value, "tcp").trim().toLowerCase(Locale.ROOT);
        if (!"tcp".equals(normalized) && !"udp".equals(normalized)) {
            throw new IllegalArgumentException("NAT protocol must be TCP or UDP.");
        }
        return normalized;
    }

    private static void requirePort(int value, String label) {
        if (value < 1 || value > 65_535) {
            throw new IllegalArgumentException(label + " must be between 1 and 65535.");
        }
    }
}
