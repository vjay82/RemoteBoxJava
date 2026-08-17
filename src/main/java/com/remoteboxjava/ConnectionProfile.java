package com.remoteboxjava;

import java.util.Locale;
import java.util.Objects;

/**
 * A saved connection to a VirtualBox host.
 *
 * <p>Both addresses are kept so switching the transport back and forth in a
 * dialog does not lose the one that is currently hidden.</p>
 */
public record ConnectionProfile(String name, String transport, String endpoint, String username, String command) {
    public static final String WEB_SERVICE = "web-service";
    public static final String COMMAND = "command";

    public ConnectionProfile {
        name = Objects.requireNonNullElse(name, "").trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("A profile name is required.");
        }
        transport = normalizeTransport(transport);
        endpoint = Objects.requireNonNullElse(endpoint, "").trim();
        username = Objects.requireNonNullElse(username, "").trim();
        command = Objects.requireNonNullElse(command, "").trim();
    }

    public static ConnectionProfile webService(String name, String endpoint, String username) {
        return new ConnectionProfile(name, WEB_SERVICE, endpoint, username, defaultCommand());
    }

    public static ConnectionProfile empty(String name) {
        return new ConnectionProfile(name, WEB_SERVICE, "http://localhost:18083", "", defaultCommand());
    }

    public boolean webService() {
        return WEB_SERVICE.equals(transport);
    }

    /** The address that applies to the selected transport. */
    public String address() {
        return webService() ? endpoint : command;
    }

    public ConnectionProfile withAddress(String address) {
        return webService()
                ? new ConnectionProfile(name, transport, address, username, command)
                : new ConnectionProfile(name, transport, endpoint, username, address);
    }

    public ConnectionProfile withName(String newName) {
        return new ConnectionProfile(newName, transport, endpoint, username, command);
    }

    public ConnectionProfile withTransport(String newTransport) {
        return new ConnectionProfile(name, newTransport, endpoint, username, command);
    }

    public ConnectionProfile withUsername(String newUsername) {
        return new ConnectionProfile(name, transport, endpoint, newUsername, command);
    }

    /** Label for the address field, which differs per transport. */
    public String addressLabel() {
        return addressLabel(webService());
    }

    public static String addressLabel(boolean webService) {
        return webService ? "Server URL" : "VBoxManage command";
    }

    public String transportLabel() {
        return webService() ? "VirtualBox Web Service" : "VirtualBox VBoxManage (local or SSH)";
    }

    public String describe() {
        return name + "  —  " + transportLabel() + ": " + address();
    }

    @Override
    public String toString() {
        return name;
    }

    static String defaultCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "VBoxManage.exe"
                : "VBoxManage";
    }

    private static String normalizeTransport(String value) {
        String normalized = Objects.requireNonNullElse(value, WEB_SERVICE).trim().toLowerCase(Locale.ROOT);
        return COMMAND.equals(normalized) ? COMMAND : WEB_SERVICE;
    }
}
