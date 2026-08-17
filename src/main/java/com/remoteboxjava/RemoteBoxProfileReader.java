package com.remoteboxjava;

import com.remoteboxjava.VBoxManageClient.VBoxException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reads configuration from an existing RemoteBox 3.7 installation.
 *
 * <p>RemoteBox stores its settings in {@code remotebox.conf} and its connection
 * profiles in {@code remotebox-profiles.conf}. Profile fields are separated by
 * ASCII Bell (0x07) and password bytes are obfuscated with a reversible XOR
 * sequence; this class implements the same format so both clients can share one
 * profile source. All plausible install locations are searched, including every
 * WSL distribution when running on Windows.</p>
 */
public final class RemoteBoxProfileReader {
    private static final Logger LOG = LogManager.getLogger(RemoteBoxProfileReader.class);
    private static final char FIELD_SEPARATOR = 7;
    private static final String CONFIG_FILE = "remotebox.conf";
    private static final String PROFILES_FILE = "remotebox-profiles.conf";
    private static final List<String> UNIX_DIRECTORIES = List.of("$HOME/.config", "$HOME/.remotebox");

    private RemoteBoxProfileReader() {
    }

    /**
     * Reads the settings worth importing into this application's own configuration.
     * Only the first start does this, so the cost of probing WSL is paid once.
     */
    public static Optional<ImportedSettings> importSettings() {
        List<Location> locations = locations();
        List<Optional<String>> configurations = readInParallel(locations, CONFIG_FILE);
        for (int index = 0; index < locations.size(); index++) {
            Optional<String> configuration = configurations.get(index);
            if (configuration.isEmpty()) {
                continue;
            }
            Location location = locations.get(index);
            LOG.info("Found a RemoteBox configuration at {}.", location.describe());
            String profileName = value(configuration.get(), "AUTOCONNPROF", "").trim();
            String endpoint = "";
            String username = "";
            char[] password = new char[0];
            Optional<String> profiles = location.read(PROFILES_FILE);
            if (profiles.isPresent()) {
                String[] fields = profileFields(profiles.get(), profileName);
                if (fields != null) {
                    endpoint = fields[1];
                    username = fields[2];
                    try {
                        password = decodePassword(fields[3], fields[4]);
                    } catch (VBoxException exception) {
                        // An unreadable password only means the user has to type it once.
                        LOG.warn("Could not decode the RemoteBox password of profile '{}'.", profileName, exception);
                    }
                }
            }
            return Optional.of(new ImportedSettings(location.describe(), profileName, endpoint, username, password,
                    displaySettings(configuration.get())));
        }
        return Optional.empty();
    }

    /**
     * Reads the same file from every candidate location at once. Each WSL location
     * costs a process start, and running them one after another dominated start-up.
     */
    private static List<Optional<String>> readInParallel(List<Location> locations, String fileName) {
        if (locations.size() < 2) {
            return locations.stream().map(location -> location.read(fileName)).toList();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(locations.size(), 8), runnable -> {
            Thread worker = new Thread(runnable, "RemoteBox-profile-probe");
            worker.setDaemon(true);
            return worker;
        });
        try {
            List<Future<Optional<String>>> futures = locations.stream()
                    .map(location -> pool.submit(() -> location.read(fileName)))
                    .toList();
            List<Optional<String>> contents = new ArrayList<>(futures.size());
            for (Future<Optional<String>> future : futures) {
                try {
                    contents.add(future.get());
                } catch (ExecutionException exception) {
                    LOG.debug("Probing a RemoteBox location failed.", exception);
                    contents.add(Optional.empty());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    LOG.debug("Probing the RemoteBox locations was interrupted.", exception);
                    contents.add(Optional.empty());
                }
            }
            return contents;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Display-client command templates for a host without RemoteBox installed.
     */
    public static DisplaySettings defaultDisplaySettings() {
        boolean windows = isWindows();
        String rdpClient = windows
                ? "mstsc.exe /w:%X /h:%Y /v:%h:%p"
                : "xfreerdp /w:%X /h:%Y /v:%h:%p";
        return new DisplaySettings(rdpClient, "vncviewer %h:%p", 1800, 950, 32, windows, true, true);
    }

    static DisplaySettings displaySettings(String configuration) {
        DisplaySettings defaults = defaultDisplaySettings();
        return new DisplaySettings(
                value(configuration, "RDPCLIENT", defaults.rdpClient()),
                value(configuration, "VNCCLIENT", defaults.vncClient()),
                integerValue(configuration, "AUTOHINTDISPX", defaults.width()),
                integerValue(configuration, "AUTOHINTDISPY", defaults.height()),
                integerValue(configuration, "AUTOHINTDISPD", defaults.depth()),
                defaults.useMstsc(),
                defaults.autoScale(),
                defaults.shareClipboard());
    }

    static String[] profileFields(String profiles, String profileName) {
        if (profileName.isBlank()) {
            return null;
        }
        for (String line : profiles.split("\\R")) {
            String[] fields = line.split(String.valueOf(FIELD_SEPARATOR), -1);
            if (fields.length >= 5 && profileName.equals(fields[0])) {
                return fields;
            }
        }
        return null;
    }

    private static String value(String configuration, String key, String fallback) {
        String prefix = key + "=";
        return configuration.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElse(fallback);
    }

    private static int integerValue(String configuration, String key, int fallback) {
        try {
            return Integer.parseInt(value(configuration, key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Every place a RemoteBox configuration may live, most specific first.
     */
    static List<Location> locations() {
        List<Location> locations = new ArrayList<>();
        for (Path directory : localDirectories()) {
            locations.add(new LocalLocation(directory));
        }
        if (isWindows()) {
            // RemoteBox is a Perl/GTK application, so on Windows it usually runs inside WSL.
            locations.add(new WslLocation(null, "$HOME/.config"));
            locations.add(new WslLocation(null, "$HOME/.remotebox"));
            for (String distribution : wslDistributions()) {
                for (String directory : UNIX_DIRECTORIES) {
                    locations.add(new WslLocation(distribution, directory));
                }
            }
        }
        return locations;
    }

    private static List<Path> localDirectories() {
        Path home = Path.of(System.getProperty("user.home", "."));
        List<Path> directories = new ArrayList<>();
        String configHome = System.getenv("XDG_CONFIG_HOME");
        if (configHome != null && !configHome.isBlank()) {
            directories.add(Path.of(configHome));
        }
        directories.add(home.resolve(".config"));
        directories.add(home.resolve(".remotebox"));
        if (isMac()) {
            directories.add(home.resolve(Path.of("Library", "Application Support", "remotebox")));
        }
        return directories;
    }

    private static List<String> wslDistributions() {
        // wsl.exe --list writes UTF-16LE.
        Optional<String> output = runCommand(List.of("wsl.exe", "--list", "--quiet"), StandardCharsets.UTF_16LE);
        if (output.isEmpty()) {
            return List.of();
        }
        return output.get().lines()
                .map(line -> line.replace("\u0000", "").trim())
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return operatingSystem.contains("mac") || operatingSystem.contains("darwin");
    }

    private static Optional<String> runCommand(List<String> command, Charset charset) {
        long started = System.nanoTime();
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("remotebox-command-", ".log");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                LOG.warn("{} timed out.", String.join(" ", command));
                return Optional.empty();
            }
            LOG.debug("{} exited with {} after {} ms.", String.join(" ", command), process.exitValue(),
                    (System.nanoTime() - started) / 1_000_000L);
            return process.exitValue() == 0
                    ? Optional.of(Files.readString(outputFile, charset))
                    : Optional.empty();
        } catch (IOException exception) {
            LOG.debug("Could not run {}.", String.join(" ", command), exception);
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOG.debug("{} was interrupted.", String.join(" ", command));
            return Optional.empty();
        } finally {
            if (outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException exception) {
                    LOG.debug("Could not delete the temporary output file {}.", outputFile, exception);
                }
            }
        }
    }

    /**
     * Mirrors RemoteBox's rbox_glue.pl xor_pass routine.
     */
    private static char[] decodePassword(String encodedValues, String key) throws VBoxException {
        String trimmedValues = encodedValues.trim();
        if (trimmedValues.isEmpty()) {
            return new char[0];
        }

        String[] values = trimmedValues.split("\\s+");
        byte[] encrypted = new byte[values.length];
        try {
            for (int index = 0; index < values.length; index++) {
                encrypted[index] = (byte) Integer.parseInt(values[index]);
            }
        } catch (NumberFormatException exception) {
            throw new VBoxException("The saved RemoteBox profile password has an invalid format.", exception);
        }

        if (key.length() < encrypted.length) {
            throw new VBoxException("The saved RemoteBox profile password key is incomplete.");
        }

        char[] password = new char[encrypted.length];
        for (int index = 0; index < encrypted.length; index++) {
            int keyIndex = key.length() - 1 - index;
            password[index] = (char) ((encrypted[index] & 0xff) ^ key.charAt(keyIndex));
        }
        Arrays.fill(encrypted, (byte) 0);
        return password;
    }

    interface Location {
        String describe();

        Optional<String> read(String fileName);
    }

    private record LocalLocation(Path directory) implements Location {
        @Override
        public String describe() {
            return directory.toString();
        }

        @Override
        public Optional<String> read(String fileName) {
            Path path = directory.resolve(fileName);
            try {
                return Files.isReadable(path)
                        ? Optional.of(Files.readString(path, StandardCharsets.UTF_8))
                        : Optional.empty();
            } catch (IOException exception) {
                LOG.debug("Could not read {}.", path, exception);
                return Optional.empty();
            }
        }
    }

    /**
     * @param distribution {@code null} selects the default WSL distribution
     * @param directory    a fixed shell expression such as {@code $HOME/.config}
     */
    private record WslLocation(String distribution, String directory) implements Location {
        @Override
        public String describe() {
            return "WSL " + (distribution == null ? "(default)" : distribution) + ":" + directory;
        }

        @Override
        public Optional<String> read(String fileName) {
            List<String> command = new ArrayList<>(List.of("wsl.exe"));
            if (distribution != null) {
                command.add("-d");
                command.add(distribution);
            }
            // No login shell: running the user's profile scripts costs seconds per call.
            command.addAll(List.of("sh", "-c", "cat \"" + directory + "/" + fileName + "\""));
            return runCommand(command, StandardCharsets.UTF_8);
        }
    }

    /**
     * @param useMstsc       launch Microsoft's client through a generated .rdp file instead
     *                       of {@code rdpClient}, which is the only way to control window
     *                       resizing and scaling
     * @param shareClipboard asking for any local resource makes mstsc show its
     *                       "unknown publisher" prompt, so this costs a confirmation
     */
    public record DisplaySettings(String rdpClient, String vncClient, int width, int height, int depth,
                                  boolean useMstsc, boolean autoScale, boolean shareClipboard) {
    }

    /** RemoteBox values worth copying into this application's own settings. */
    public record ImportedSettings(String source, String profileName, String endpoint, String username,
                                   char[] password, DisplaySettings display) {
        public ImportedSettings {
            password = password == null ? new char[0] : password.clone();
        }

        public void clearPassword() {
            Arrays.fill(password, '\0');
        }
    }
}
