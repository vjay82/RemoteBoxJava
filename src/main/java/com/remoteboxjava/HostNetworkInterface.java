package com.remoteboxjava;

import java.util.Objects;

/**
 * A host network interface reported by VirtualBox.
 */
public record HostNetworkInterface(
        String name,
        String type,
        String ipv4Address,
        String ipv4Mask,
        String ipv6Address,
        int ipv6PrefixLength,
        boolean dhcpEnabled
) {
    public HostNetworkInterface {
        name = Objects.requireNonNullElse(name, "").trim();
        type = Objects.requireNonNullElse(type, "").trim();
        ipv4Address = Objects.requireNonNullElse(ipv4Address, "").trim();
        ipv4Mask = Objects.requireNonNullElse(ipv4Mask, "").trim();
        ipv6Address = Objects.requireNonNullElse(ipv6Address, "").trim();
        ipv6PrefixLength = Math.max(0, Math.min(128, ipv6PrefixLength));
    }
}
