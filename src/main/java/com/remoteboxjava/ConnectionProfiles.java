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
    /** Serialised as a JSON array, so every entry key below it is an index. */
    private static final String LIST_PREFIX = PREFIX + "list.";
    private static final String AUTO_CONNECT_KEY = PREFIX + "autoConnect";

    private final ApplicationSettings settings;

    public ConnectionProfiles(ApplicationSettings settings) {
        this.settings = settings;
    }

    public List<ConnectionProfile> all() {
        List<ConnectionProfile> profiles = new ArrayList<>();
        for (int index = 0; ; index++) {
            String key = LIST_PREFIX + index + ".";
            String name = settings.get(key + "name", "");
            if (name.isBlank()) {
                return profiles;
            }
            profiles.add(new ConnectionProfile(name,
                    settings.get(key + "transport", ConnectionProfile.WEB_SERVICE),
                    settings.get(key + "endpoint", ""),
                    settings.get(key + "username", ""),
                    settings.get(key + "command", ConnectionProfile.defaultCommand())));
        }
    }

    /** @return the stored password of the profile, encrypted, or an empty string */
    public String password(String profileName) {
        List<ConnectionProfile> profiles = all();
        for (int index = 0; index < profiles.size(); index++) {
            if (profiles.get(index).name().equals(profileName)) {
                return settings.get(LIST_PREFIX + index + ".password", "");
            }
        }
        return "";
    }

    public void storePassword(String profileName, String protectedPassword) {
        List<ConnectionProfile> profiles = all();
        for (int index = 0; index < profiles.size(); index++) {
            if (profiles.get(index).name().equals(profileName)) {
                settings.put(LIST_PREFIX + index + ".password", protectedPassword);
                return;
            }
        }
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
        // Rewriting the list must not silently drop the passwords it does not carry.
        Map<String, String> passwords = new LinkedHashMap<>();
        for (ConnectionProfile existing : all()) {
            String password = password(existing.name());
            if (!password.isEmpty()) {
                passwords.put(existing.name(), password);
            }
        }

        Map<String, String> values = new LinkedHashMap<>();
        boolean autoConnectExists = profiles.stream()
                .anyMatch(profile -> profile.name().equals(autoConnectName));
        values.put(AUTO_CONNECT_KEY, autoConnectExists ? autoConnectName : "");
        for (int index = 0; index < profiles.size(); index++) {
            ConnectionProfile profile = profiles.get(index);
            String key = LIST_PREFIX + index + ".";
            values.put(key + "name", profile.name());
            values.put(key + "transport", profile.transport());
            values.put(key + "endpoint", profile.endpoint());
            values.put(key + "username", profile.username());
            values.put(key + "command", profile.command());
            String password = passwords.get(profile.name());
            if (password != null) {
                values.put(key + "password", password);
            }
        }
        settings.replaceSection(PREFIX, values);
    }

    /**
     * Creates a first profile from the single connection the older settings
     * format could hold, so upgrading users keep their server.
     */
    public void migrateSingleConnection() {
        if (!all().isEmpty()) {
            adoptImportedPassword();
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
        adoptImportedPassword();
    }

    /**
     * Moves a password imported from RemoteBox into the profile it belongs to. The
     * import runs while seeding the settings, before any profile exists.
     */
    private void adoptImportedPassword() {
        String imported = settings.get(ApplicationSettings.IMPORTED_PASSWORD_KEY, "");
        if (imported.isEmpty()) {
            return;
        }
        String endpoint = settings.get("webservice.endpoint", "");
        all().stream()
                .filter(profile -> profile.webService() && profile.endpoint().equals(endpoint))
                .findFirst()
                .ifPresent(profile -> storePassword(profile.name(), imported));
        settings.remove(ApplicationSettings.IMPORTED_PASSWORD_KEY);
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
