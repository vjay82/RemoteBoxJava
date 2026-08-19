package com.remoteboxjava;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Application settings stored as JSON in the platform's own configuration
 * location: {@code %APPDATA%\RemoteBoxJava} on Windows,
 * {@code ~/Library/Application Support/RemoteBoxJava} on macOS and
 * {@code $XDG_CONFIG_HOME/remotebox-java} (or {@code ~/.config/remotebox-java})
 * elsewhere.
 *
 * <p>When no settings file exists yet the values are seeded from a previous
 * installation of this application and, failing that, from RemoteBox.</p>
 */
public final class ApplicationSettings {
    private static final Logger LOG = LogManager.getLogger(ApplicationSettings.class);
    private static final String FILE_NAME = "settings.json";
    private static final String LEGACY_FILE_NAME = "settings.properties";
    /**
     * Where the RemoteBox import parks the password until the profiles exist to
     * hold it. Encrypted by {@link SecretStore}, never written in clear text.
     */
    static final String IMPORTED_PASSWORD_KEY = "import.password";
    private static final String[] MIGRATED_KEYS = {
            "connection.profile", "connection.transport", "webservice.endpoint", "webservice.username",
            "vbox.command", "refresh.seconds", "profile.autoload", "confirm.actions", "details.extended"
    };

    private static ApplicationSettings shared;

    private final Path file;
    private final Map<String, String> values = new TreeMap<>();
    private final ImportReport importReport;

    public static synchronized ApplicationSettings shared() {
        if (shared == null) {
            shared = new ApplicationSettings(configurationDirectory());
        }
        return shared;
    }

    ApplicationSettings(Path directory) {
        this(directory, true);
    }

    /**
     * @param importExternalSources whether to seed from installations outside
     *                              {@code directory}, which tests must not do
     */
    ApplicationSettings(Path directory, boolean importExternalSources) {
        this.file = directory.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            importReport = load();
            // A file written by an older version is missing any newly added key.
            if (importReport.source() != ImportReport.Source.FAILED && fillMissingDisplaySettings()) {
                save();
            }
        } else {
            importReport = seed(directory, importExternalSources);
            save();
        }
    }

    /** Configuration directory for the current platform. */
    public static Path configurationDirectory() {
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home", "."));
        if (operatingSystem.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = appData == null || appData.isBlank()
                    ? home.resolve(Path.of("AppData", "Roaming"))
                    : Path.of(appData);
            return base.resolve("RemoteBoxJava");
        }
        if (operatingSystem.contains("mac") || operatingSystem.contains("darwin")) {
            return home.resolve(Path.of("Library", "Application Support", "RemoteBoxJava"));
        }
        String configHome = System.getenv("XDG_CONFIG_HOME");
        Path base = configHome == null || configHome.isBlank() ? home.resolve(".config") : Path.of(configHome);
        return base.resolve("remotebox-java");
    }

    public Path location() {
        return file;
    }

    /** Describes where the initial values came from. */
    public ImportReport importReport() {
        return importReport;
    }

    public String get(String key, String fallback) {
        String value = values.get(key);
        return value == null ? fallback : value;
    }

    public void put(String key, String value) {
        values.put(key, value == null ? "" : value);
        save();
    }

    public void remove(String key) {
        if (values.remove(key) != null) {
            save();
        }
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public void putInt(String key, int value) {
        put(key, Integer.toString(value));
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key, null);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    public void putBoolean(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    /**
     * Replaces every key starting with {@code prefix} in one write, so a list of
     * values cannot be left half-updated on disk.
     */
    public void replaceSection(String prefix, Map<String, String> replacement) {
        values.keySet().removeIf(key -> key.startsWith(prefix));
        replacement.forEach((key, value) -> values.put(key, value == null ? "" : value));
        save();
    }

    /**
     * Remote-display client templates, seeded from RemoteBox on first start.
     */
    public RemoteBoxProfileReader.DisplaySettings displaySettings() {
        RemoteBoxProfileReader.DisplaySettings defaults = RemoteBoxProfileReader.defaultDisplaySettings();
        return new RemoteBoxProfileReader.DisplaySettings(
                get("display.rdpClient", defaults.rdpClient()),
                get("display.vncClient", defaults.vncClient()),
                getInt("display.width", defaults.width()),
                getInt("display.height", defaults.height()),
                getInt("display.depth", defaults.depth()),
                getBoolean("display.useMstsc", defaults.useMstsc()),
                getBoolean("display.autoScale", defaults.autoScale()),
                getBoolean("display.shareClipboard", defaults.shareClipboard()));
    }

    private ImportReport load() {
        try {
            values.putAll(fromJson(Files.readString(file, StandardCharsets.UTF_8)));
            LOG.debug("Loaded {} settings from {}.", values.size(), file);
            return ImportReport.none();
        } catch (IOException | IllegalArgumentException exception) {
            LOG.error("Could not read the settings file {}; continuing with defaults.", file, exception);
            return ImportReport.failure("Could not read " + file + ": " + exception.getMessage());
        }
    }

    private ImportReport seed(Path directory, boolean importExternalSources) {
        ImportReport migrated = migrateLegacyFile(directory.resolve(LEGACY_FILE_NAME));
        if (migrated == null && importExternalSources) {
            migrated = migrateLegacyPreferences();
        }
        if (!importExternalSources) {
            fillMissingDisplaySettings();
            return migrated == null ? ImportReport.none() : migrated;
        }
        if (migrated != null) {
            // Legacy settings never held a password, so ask RemoteBox for one anyway.
            importRemoteBoxPassword();
            fillMissingDisplaySettings();
            return migrated;
        }
        return importFromRemoteBox();
    }

    private void importRemoteBoxPassword() {
        RemoteBoxProfileReader.importSettings().ifPresent(imported -> {
            String protectedPassword = SecretStore.protect(imported.password());
            imported.clearPassword();
            if (!protectedPassword.isEmpty()) {
                values.put(IMPORTED_PASSWORD_KEY, protectedPassword);
            }
        });
    }

    private ImportReport migrateLegacyFile(Path legacy) {
        if (!Files.isRegularFile(legacy)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(legacy)) {
            properties.load(input);
        } catch (IOException exception) {
            LOG.warn("Could not read the legacy settings file {}.", legacy, exception);
            return null;
        }
        properties.stringPropertyNames().forEach(key -> values.put(key, properties.getProperty(key)));
        return values.isEmpty() ? null
                : new ImportReport(ImportReport.Source.PREVIOUS_VERSION, legacy.toString(), List.of());
    }

    /**
     * Earlier versions used {@link Preferences}, which is the Windows registry on
     * this platform rather than a file the user can back up.
     */
    private ImportReport migrateLegacyPreferences() {
        try {
            Preferences legacy = Preferences.userNodeForPackage(RemoteBoxApplication.class);
            if (legacy.keys().length == 0) {
                return null;
            }
            for (String key : MIGRATED_KEYS) {
                String value = legacy.get(key, null);
                if (value != null) {
                    values.put(key, value);
                }
            }
            return values.isEmpty() ? null
                    : new ImportReport(ImportReport.Source.PREVIOUS_VERSION, "the previous installation", List.of());
        } catch (BackingStoreException | SecurityException exception) {
            LOG.debug("No readable settings of a previous installation.", exception);
            return null;
        }
    }

    private ImportReport importFromRemoteBox() {
        Optional<RemoteBoxProfileReader.ImportedSettings> imported = RemoteBoxProfileReader.importSettings();
        if (imported.isEmpty()) {
            fillMissingDisplaySettings();
            return ImportReport.none();
        }

        RemoteBoxProfileReader.ImportedSettings settings = imported.get();
        List<String> details = new ArrayList<>();
        if (!settings.profileName().isBlank()) {
            values.put("connection.profile", settings.profileName());
            values.put("connection.transport", "web-service");
            details.add("Connection profile: " + settings.profileName());
        }
        if (!settings.endpoint().isBlank()) {
            values.put("webservice.endpoint", settings.endpoint());
            details.add("Server URL: " + settings.endpoint());
        }
        if (!settings.username().isBlank()) {
            values.put("webservice.username", settings.username());
            details.add("User name: " + settings.username());
        }
        String protectedPassword = SecretStore.protect(settings.password());
        settings.clearPassword();
        if (!protectedPassword.isEmpty()) {
            values.put(IMPORTED_PASSWORD_KEY, protectedPassword);
            details.add("Password: stored encrypted for this Windows account");
        }
        applyDisplaySettings(settings.display());
        details.add("RDP client: " + settings.display().rdpClient());
        details.add("VNC client: " + settings.display().vncClient());
        LOG.info("Imported the settings of RemoteBox at {}.", settings.source());
        return new ImportReport(ImportReport.Source.REMOTEBOX, settings.source(), List.copyOf(details));
    }

    private void applyDisplaySettings(RemoteBoxProfileReader.DisplaySettings display) {
        values.put("display.rdpClient", display.rdpClient());
        values.put("display.vncClient", display.vncClient());
        values.put("display.width", Integer.toString(display.width()));
        values.put("display.height", Integer.toString(display.height()));
        values.put("display.depth", Integer.toString(display.depth()));
        values.putIfAbsent("display.useMstsc", Boolean.toString(display.useMstsc()));
        values.putIfAbsent("display.autoScale", Boolean.toString(display.autoScale()));
        values.putIfAbsent("display.shareClipboard", Boolean.toString(display.shareClipboard()));
    }

    private boolean fillMissingDisplaySettings() {
        RemoteBoxProfileReader.DisplaySettings defaults = RemoteBoxProfileReader.defaultDisplaySettings();
        Map<String, String> missing = new LinkedHashMap<>();
        missing.put("display.rdpClient", defaults.rdpClient());
        missing.put("display.vncClient", defaults.vncClient());
        missing.put("display.width", Integer.toString(defaults.width()));
        missing.put("display.height", Integer.toString(defaults.height()));
        missing.put("display.depth", Integer.toString(defaults.depth()));
        missing.put("display.useMstsc", Boolean.toString(defaults.useMstsc()));
        missing.put("display.autoScale", Boolean.toString(defaults.autoScale()));
        missing.put("display.shareClipboard", Boolean.toString(defaults.shareClipboard()));

        boolean added = false;
        for (Map.Entry<String, String> entry : missing.entrySet()) {
            added |= values.putIfAbsent(entry.getKey(), entry.getValue()) == null;
        }
        return added;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(values), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            // Settings are a convenience; a read-only profile must not stop the client.
            LOG.warn("Could not write the settings file {}.", file, exception);
        }
    }

    static String toJson(Map<String, String> values) {
        StringBuilder json = new StringBuilder();
        writeValue(json, tree(values), 0);
        return json.append('\n').toString();
    }

    /**
     * Groups the dotted keys into the nested objects they describe, so the file
     * reads as real JSON instead of a properties list. A node whose members are
     * all numbers becomes an array.
     */
    private static Map<String, Object> tree(Map<String, String> values) {
        Map<String, Object> root = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String[] segments = entry.getKey().split("\\.");
            Map<String, Object> node = root;
            for (int index = 0; index < segments.length - 1; index++) {
                Object child = node.get(segments[index]);
                if (!(child instanceof Map)) {
                    child = new TreeMap<String, Object>();
                    node.put(segments[index], child);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) child;
                node = next;
            }
            node.putIfAbsent(segments[segments.length - 1], entry.getValue());
        }
        return root;
    }

    private static void writeValue(StringBuilder json, Object node, int depth) {
        if (!(node instanceof Map<?, ?> members)) {
            json.append(jsonValue((String) node));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> children = (Map<String, Object>) members;
        if (children.isEmpty()) {
            json.append("{}");
            return;
        }
        boolean array = children.keySet().stream().allMatch(key -> key.matches("\\d+"));
        List<String> keys = array
                ? children.keySet().stream().sorted(Comparator.comparingInt(Integer::parseInt)).toList()
                : List.copyOf(children.keySet());

        json.append(array ? "[\n" : "{\n");
        for (int index = 0; index < keys.size(); index++) {
            indent(json, depth + 1);
            if (!array) {
                appendJsonString(json, keys.get(index));
                json.append(": ");
            }
            writeValue(json, children.get(keys.get(index)), depth + 1);
            json.append(index < keys.size() - 1 ? ",\n" : "\n");
        }
        indent(json, depth);
        json.append(array ? ']' : '}');
    }

    private static void indent(StringBuilder json, int depth) {
        json.append("  ".repeat(depth));
    }

    /** Writes integers and booleans as native JSON values, everything else as a string. */
    private static String jsonValue(String value) {
        if ("true".equals(value) || "false".equals(value) || value.matches("-?(0|[1-9]\\d{0,17})")) {
            return value;
        }
        StringBuilder text = new StringBuilder();
        appendJsonString(text, value);
        return text.toString();
    }

    private static void appendJsonString(StringBuilder target, String value) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                default -> {
                    if (character < 0x20) {
                        target.append(String.format("\\u%04x", (int) character));
                    } else {
                        target.append(character);
                    }
                }
            }
        }
        target.append('"');
    }

    /**
     * Reads a JSON object into dotted keys; nested objects and arrays are
     * flattened, so a file written by an older, flat version still loads.
     */
    static Map<String, String> fromJson(String text) {
        return new JsonReader(text).readSettings();
    }

    private static final class JsonReader {
        private final String text;
        private int position;

        private JsonReader(String text) {
            this.text = text;
        }

        private Map<String, String> readSettings() {
            Map<String, String> values = new TreeMap<>();
            skipWhitespace();
            readObject("", values);
            return values;
        }

        private void readObject(String prefix, Map<String, String> values) {
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                readMember(prefix.isEmpty() ? key : prefix + "." + key, values);
                skipWhitespace();
                char separator = peek();
                position++;
                if (separator == '}') {
                    return;
                }
                if (separator != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at offset " + (position - 1));
                }
                skipWhitespace();
                if (peek() == '}') {
                    position++;
                    return;
                }
            }
        }

        private void readArray(String prefix, Map<String, String> values) {
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return;
            }
            int index = 0;
            while (true) {
                readMember(prefix + "." + index++, values);
                skipWhitespace();
                char separator = peek();
                position++;
                if (separator == ']') {
                    return;
                }
                if (separator != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at offset " + (position - 1));
                }
                skipWhitespace();
                if (peek() == ']') {
                    position++;
                    return;
                }
            }
        }

        private void readMember(String key, Map<String, String> values) {
            skipWhitespace();
            switch (peek()) {
                case '{' -> readObject(key, values);
                case '[' -> readArray(key, values);
                default -> values.put(key, readValue());
            }
        }

        private String readValue() {
            if (peek() == '"') {
                return readString();
            }
            int start = position;
            while (position < text.length() && ",}]\n\r\t ".indexOf(text.charAt(position)) < 0) {
                position++;
            }
            String literal = text.substring(start, position).trim();
            if (literal.isEmpty()) {
                throw new IllegalArgumentException("Missing value at offset " + start);
            }
            return "null".equals(literal) ? "" : literal;
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (true) {
                char character = peek();
                position++;
                if (character == '"') {
                    return value.toString();
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                char escape = peek();
                position++;
                switch (escape) {
                    case '"', '\\', '/' -> value.append(escape);
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw new IllegalArgumentException("Truncated escape at offset " + position);
                        }
                        value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> throw new IllegalArgumentException("Unsupported escape '\\" + escape + "'");
                }
            }
        }

        private char peek() {
            if (position >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of settings file");
            }
            return text.charAt(position);
        }

        private void expect(char expected) {
            if (peek() != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at offset " + position);
            }
            position++;
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }
    }

    /**
     * Where the settings came from when the configuration file was created.
     *
     * @param origin  the RemoteBox location or legacy file the values came from
     * @param details human-readable list of the imported values
     */
    public record ImportReport(Source source, String origin, List<String> details) {
        public enum Source { NONE, PREVIOUS_VERSION, REMOTEBOX, FAILED }

        static ImportReport none() {
            return new ImportReport(Source.NONE, "", List.of());
        }

        static ImportReport failure(String message) {
            return new ImportReport(Source.FAILED, message, List.of());
        }

        public boolean fromRemoteBox() {
            return source == Source.REMOTEBOX;
        }

        public String summary() {
            return switch (source) {
                case REMOTEBOX -> "Imported RemoteBox settings from " + origin + ".";
                case PREVIOUS_VERSION -> "Migrated settings from " + origin + ".";
                case FAILED -> origin;
                case NONE -> "";
            };
        }
    }
}
