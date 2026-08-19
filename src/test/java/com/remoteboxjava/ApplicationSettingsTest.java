package com.remoteboxjava;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationSettingsTest {
    @Test
    void settingsArePersistedAsJsonInTheGivenDirectory(@TempDir Path directory) throws Exception {
        ApplicationSettings settings = new ApplicationSettings(directory, false);
        settings.put("webservice.endpoint", "http://vbox.example.test:18083");
        settings.putInt("refresh.seconds", 45);
        settings.putBoolean("confirm.actions", false);

        Path file = directory.resolve("settings.json");
        assertTrue(Files.isRegularFile(file));
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("\"refresh\": {"));
        assertTrue(json.contains("\"seconds\": 45"));
        assertTrue(json.contains("\"actions\": false"));
        assertTrue(json.contains("\"endpoint\": \"http://vbox.example.test:18083\""));

        ApplicationSettings reloaded = new ApplicationSettings(directory, false);
        assertEquals("http://vbox.example.test:18083", reloaded.get("webservice.endpoint", ""));
        assertEquals(45, reloaded.getInt("refresh.seconds", 30));
        assertFalse(reloaded.getBoolean("confirm.actions", true));
        assertEquals(ApplicationSettings.ImportReport.Source.NONE, reloaded.importReport().source());
    }

    @Test
    void jsonRoundTripPreservesEscapedValues() {
        Map<String, String> values = new TreeMap<>();
        values.put("display.rdpClient", "mstsc.exe /w:%X /v:\"%h\":%p");
        values.put("note", "line\nbreak\ttab\\slash");
        values.put("empty", "");

        Map<String, String> restored = ApplicationSettings.fromJson(ApplicationSettings.toJson(values));

        assertEquals(values, restored);
    }

    @Test
    void handWrittenJsonIsAccepted() {
        Map<String, String> values = ApplicationSettings.fromJson("""
                {
                  "connection.transport" : "web-service",
                  "refresh.seconds": 20,
                  "profile.autoload": true,
                  "unset": null
                }
                """);

        assertEquals("web-service", values.get("connection.transport"));
        assertEquals("20", values.get("refresh.seconds"));
        assertEquals("true", values.get("profile.autoload"));
        assertEquals("", values.get("unset"));
    }

    @Test
    void compactArraysOfLiteralsAreAccepted() {
        Map<String, String> values = ApplicationSettings.fromJson("{\"ports\":[18083,18084],\"on\":[true]}");

        assertEquals("18083", values.get("ports.0"));
        assertEquals("18084", values.get("ports.1"));
        assertEquals("true", values.get("on.0"));
    }

    @Test
    void malformedSettingsFileIsNotOverwritten(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("settings.json");
        String malformed = "{\n  \"webservice.endpoint\": \"http://recover-me\",\n";
        Files.writeString(file, malformed, StandardCharsets.UTF_8);

        ApplicationSettings settings = new ApplicationSettings(directory, false);

        assertEquals(ApplicationSettings.ImportReport.Source.FAILED, settings.importReport().source());
        assertEquals(malformed, Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(settings.displaySettings().width() > 0);
    }

    @Test
    void settingsFileOfAPreviousVersionIsMigrated(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("settings.properties"),
                "webservice.endpoint=http://legacy.example.test:18083\nrefresh.seconds=15\n",
                StandardCharsets.UTF_8);

        ApplicationSettings settings = new ApplicationSettings(directory, false);

        assertEquals("http://legacy.example.test:18083", settings.get("webservice.endpoint", ""));
        assertEquals(15, settings.getInt("refresh.seconds", 30));
        assertEquals(ApplicationSettings.ImportReport.Source.PREVIOUS_VERSION, settings.importReport().source());
        assertFalse(settings.importReport().fromRemoteBox());
        assertTrue(Files.isRegularFile(directory.resolve("settings.json")));
    }

    @Test
    void freshInstallationSeedsDisplayDefaults(@TempDir Path directory) {
        ApplicationSettings settings = new ApplicationSettings(directory, false);

        RemoteBoxProfileReader.DisplaySettings display = settings.displaySettings();
        assertTrue(display.rdpClient().contains("%h"));
        assertTrue(display.vncClient().contains("%p"));
        assertTrue(display.width() > 0);
        assertTrue(display.depth() > 0);
    }

    @Test
    void configurationDirectoryMatchesThePlatformConvention() {
        Path directory = ApplicationSettings.configurationDirectory();
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        assertNotNull(directory.getParent());
        if (operatingSystem.contains("win")) {
            assertEquals("RemoteBoxJava", directory.getFileName().toString());
            assertTrue(directory.toString().contains("Roaming"));
        } else if (operatingSystem.contains("mac") || operatingSystem.contains("darwin")) {
            assertEquals("RemoteBoxJava", directory.getFileName().toString());
            assertTrue(directory.toString().contains("Application Support"));
        } else {
            assertEquals("remotebox-java", directory.getFileName().toString());
        }
    }

    @Test
    void remoteBoxDisplaySettingsAreParsedFromConfiguration() {
        String configuration = """
                AUTOCONNPROF=Laptop
                RDPCLIENT=rdesktop -g %Xx%Y -a %D %h:%p
                VNCCLIENT=vncviewer %h:%p
                AUTOHINTDISPX=1600
                AUTOHINTDISPY=900
                AUTOHINTDISPD=24
                """;

        RemoteBoxProfileReader.DisplaySettings display = RemoteBoxProfileReader.displaySettings(configuration);

        assertEquals("rdesktop -g %Xx%Y -a %D %h:%p", display.rdpClient());
        assertEquals(1600, display.width());
        assertEquals(900, display.height());
        assertEquals(24, display.depth());
    }

    @Test
    void remoteBoxProfileFieldsAreSeparatedByBell() {
        String profiles = "Laptop\u0007http://vbox.example.test:18083\u0007vjay\u000712 34\u0007key\n"
                + "Other\u0007http://other.example.test:18083\u0007root\u0007\u0007key\n";

        String[] fields = RemoteBoxProfileReader.profileFields(profiles, "Other");

        assertNotNull(fields);
        assertEquals("http://other.example.test:18083", fields[1]);
        assertEquals("root", fields[2]);
    }
}
