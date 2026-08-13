package com.remoteboxjava;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stores the connection profiles and the one to connect to at startup inside
 * {@link ApplicationSettings}.
 */
public final class ConnectionProfiles {
    static final String PREFIX = "profiles.";
    private static final String COUNT_KEY = PREFIX + "count";
    private static final String AUTO_CONNECT_KEY = PREFIX + "autoConnect";

    private final ApplicationSettings settings;

    public ConnectionProfiles(ApplicationSettings settings) {
        this.settings = settings;
    }

    public List<ConnectionProfile> all() {
        int count = settings.getInt(COUNT_KEY, 0);
        List<ConnectionProfile> profiles = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String key = PREFIX + index + ".";
            String name = settings.get(key + "name", "");
            if (name.isBlank()) {
                continue;
            }
            profiles.add(new ConnectionProfile(name,
                    settings.get(key + "transport", ConnectionProfile.WEB_SERVICE),
                    settings.get(key + "endpoint", ""),
                    settings.get(key + "username", ""),
                    settings.get(key + "command", ConnectionProfile.defaultCommand())));
        }
        return profiles;
    }

    public Optional<ConnectionProfile> byName(String name) {
        return name == null || name.isBlank()
                ? Optional.empty()
                : all().stream().filter(profile -> profile.name().equals(name)).findFirst();
    }

    /** Name of the profile to connect to at startup, or empty for none. */
    public String autoConnectName() {
        return settings.get(AUTO_CONNECT_KEY, "");
    }

    public Optional<ConnectionProfile> autoConnectProfile() {
        return byName(autoConnectName());
    }

    public void replaceAll(List<ConnectionProfile> profiles, String autoConnectName) {
        Set<String> names = new HashSet<>();
        for (ConnectionProfile profile : profiles) {
            if (!names.add(profile.name())) {
                throw new IllegalArgumentException("Profile names must be unique: " + profile.name());
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(COUNT_KEY, Integer.toString(profiles.size()));
        boolean autoConnectExists = profiles.stream()
                .anyMatch(profile -> profile.name().equals(autoConnectName));
        values.put(AUTO_CONNECT_KEY, autoConnectExists ? autoConnectName : "");
        for (int index = 0; index < profiles.size(); index++) {
            ConnectionProfile profile = profiles.get(index);
            String key = PREFIX + index + ".";
            values.put(key + "name", profile.name());
            values.put(key + "transport", profile.transport());
            values.put(key + "endpoint", profile.endpoint());
            values.put(key + "username", profile.username());
            values.put(key + "command", profile.command());
        }
        settings.replaceSection(PREFIX, values);
    }

    /**
     * Creates a first profile from the single connection the older settings
     * format could hold, so upgrading users keep their server.
     */
    public void migrateSingleConnection() {
        if (settings.getInt(COUNT_KEY, 0) > 0) {
            return;
        }
        String endpoint = settings.get("webservice.endpoint", "");
        String command = settings.get("vbox.command", "");
        if (endpoint.isBlank() && command.isBlank()) {
            return;
        }
        ConnectionProfile profile = new ConnectionProfile(
                settings.get("connection.profile", "Default"),
                settings.get("connection.transport", ConnectionProfile.WEB_SERVICE),
                endpoint,
                settings.get("webservice.username", ""),
                command.isBlank() ? ConnectionProfile.defaultCommand() : command);
        // Older versions connected on start whenever a RemoteBox profile was loaded.
        String autoConnect = settings.getBoolean("profile.autoload", true) ? profile.name() : "";
        replaceAll(List.of(profile), autoConnect);
    }

    /** Remembers the profile the user last connected with. */
    public void rememberSelection(ConnectionProfile profile) {
        settings.put("connection.profile", profile.name());
        settings.put("connection.transport", profile.transport());
        settings.put("webservice.endpoint", profile.endpoint());
        settings.put("webservice.username", profile.username());
        settings.put("vbox.command", profile.command());
    }

    /** The profile a connection dialog should start from. */
    public ConnectionProfile selected() {
        return byName(settings.get("connection.profile", ""))
                .or(this::autoConnectProfile)
                .or(() -> all().stream().findFirst())
                .orElseGet(() -> new ConnectionProfile(
                        settings.get("connection.profile", "Default"),
                        settings.get("connection.transport", ConnectionProfile.WEB_SERVICE),
                        settings.get("webservice.endpoint", "http://localhost:18083"),
                        settings.get("webservice.username", ""),
                        settings.get("vbox.command", ConnectionProfile.defaultCommand())));
    }
}
