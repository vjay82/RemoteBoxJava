package com.remoteboxjava;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Log4j2 bootstrap for the standalone launcher.
 *
 * <p>{@link #configure()} must only ever be called from
 * {@link RemoteBoxApplication#main(String[])}. When autoMATE embeds RemoteBox as
 * a library it has already configured log4j2 for the whole process, and
 * reconfiguring it here would redirect the host's logging into RemoteBox's own
 * file.</p>
 */
final class Logging {

    private static final String CONFIGURATION_FILE = "log4jconfiguration.xml";

    private Logging() {
    }

    /**
     * Writes the log4j2 configuration into the configuration directory, pointing
     * it at {@code <configuration directory>/log}, and activates it. Failures are
     * reported on stderr and leave log4j2 at its own defaults, because logging
     * must never stop the application from starting.
     */
    static void configure() {
        try {
            Path home = ApplicationSettings.configurationDirectory();
            Path logFolder = home.resolve("log");
            Files.createDirectories(logFolder);

            Path configuration = home.resolve(CONFIGURATION_FILE);
            String content = template().replace("${logPath}", logFolder + java.io.File.separator);
            if (!Files.exists(configuration) || !content.equals(readOrNull(configuration))) {
                Files.writeString(configuration, content, StandardCharsets.UTF_8);
            }

            // Reconfigure the context of this class loader: loading this class already
            // started it with the default configuration, and Configurator's own helpers
            // resolve a different, unused context.
            LoggerContext context = (LoggerContext) LogManager.getContext(Logging.class.getClassLoader(), false);
            context.setConfigLocation(configuration.toUri());
        } catch (Exception exception) {
            System.err.println("Could not configure logging: " + exception);
        }
    }

    private static String template() throws Exception {
        try (InputStream resource = Logging.class.getResourceAsStream("/" + CONFIGURATION_FILE)) {
            if (resource == null) {
                throw new IllegalStateException(CONFIGURATION_FILE + " is missing from the application resources.");
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readOrNull(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // A missing or unreadable file simply gets rewritten.
            return null;
        }
    }
}
