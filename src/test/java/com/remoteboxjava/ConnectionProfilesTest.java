package com.remoteboxjava;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionProfilesTest {
    @Test
    void switchingTransportKeepsBothAddresses() {
        ConnectionProfile profile = new ConnectionProfile("Laptop", ConnectionProfile.WEB_SERVICE,
                "http://vbox.example.test:18083", "vjay", "VBoxManage.exe");

        assertEquals("http://vbox.example.test:18083", profile.address());
        assertEquals("Server URL", profile.addressLabel());

        ConnectionProfile viaSsh = profile.withTransport(ConnectionProfile.COMMAND)
                .withAddress("ssh vbox@example.test VBoxManage");
        assertEquals("ssh vbox@example.test VBoxManage", viaSsh.address());
        assertEquals("VBoxManage command", viaSsh.addressLabel());
        // Switching back must not have lost the web-service URL.
        assertEquals("http://vbox.example.test:18083",
                viaSsh.withTransport(ConnectionProfile.WEB_SERVICE).address());
    }

    @Test
    void profileNameIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionProfile("  ", ConnectionProfile.WEB_SERVICE, "http://x", "u", "VBoxManage"));
    }

    @Test
    void profilesAndAutoConnectSurviveAReload(@TempDir Path directory) {
        ConnectionProfiles store = new ConnectionProfiles(new ApplicationSettings(directory, false));
        ConnectionProfile laptop = ConnectionProfile.webService("Laptop", "http://laptop:18083", "vjay");
        ConnectionProfile lab = new ConnectionProfile("Lab", ConnectionProfile.COMMAND, "", "",
                "ssh lab VBoxManage");

        store.replaceAll(List.of(laptop, lab), "Lab");

        ConnectionProfiles reloaded = new ConnectionProfiles(new ApplicationSettings(directory, false));
        assertEquals(List.of("Laptop", "Lab"), reloaded.all().stream().map(ConnectionProfile::name).toList());
        assertEquals("Lab", reloaded.autoConnectName());
        assertEquals("ssh lab VBoxManage", reloaded.autoConnectProfile().orElseThrow().address());
        assertTrue(reloaded.byName("Laptop").isPresent());
        assertTrue(reloaded.byName("Missing").isEmpty());
    }

    @Test
    void removingTheStartupProfileClearsAutoConnect(@TempDir Path directory) {
        ConnectionProfiles store = new ConnectionProfiles(new ApplicationSettings(directory, false));
        store.replaceAll(List.of(ConnectionProfile.webService("Laptop", "http://laptop:18083", "vjay")), "Laptop");

        store.replaceAll(List.of(ConnectionProfile.webService("Other", "http://other:18083", "root")), "Laptop");

        assertEquals("", store.autoConnectName());
        assertTrue(store.autoConnectProfile().isEmpty());
    }

    @Test
    void shrinkingTheListDoesNotLeaveOrphanedEntries(@TempDir Path directory) {
        ApplicationSettings settings = new ApplicationSettings(directory, false);
        ConnectionProfiles store = new ConnectionProfiles(settings);
        store.replaceAll(List.of(
                ConnectionProfile.webService("A", "http://a:18083", "a"),
                ConnectionProfile.webService("B", "http://b:18083", "b"),
                ConnectionProfile.webService("C", "http://c:18083", "c")), "C");

        store.replaceAll(List.of(ConnectionProfile.webService("A", "http://a:18083", "a")), "A");

        assertEquals(1, store.all().size());
        assertEquals("", settings.get("profiles.1.name", ""));
        assertEquals("", settings.get("profiles.2.name", ""));
    }

    @Test
    void duplicateProfileNamesAreRejected(@TempDir Path directory) {
        ConnectionProfiles store = new ConnectionProfiles(new ApplicationSettings(directory, false));
        List<ConnectionProfile> duplicates = List.of(
                ConnectionProfile.webService("Lab", "http://first:18083", "first"),
                ConnectionProfile.webService("Lab", "http://second:18083", "second"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> store.replaceAll(duplicates, "Lab"));

        assertTrue(exception.getMessage().contains("Lab"));
        assertTrue(store.all().isEmpty());
    }

    @Test
    void aSingleLegacyConnectionBecomesTheFirstProfile(@TempDir Path directory) {
        ApplicationSettings settings = new ApplicationSettings(directory, false);
        settings.put("connection.profile", "Laptop");
        settings.put("connection.transport", "web-service");
        settings.put("webservice.endpoint", "http://legacy:18083");
        settings.put("webservice.username", "vjay");
        settings.putBoolean("profile.autoload", true);

        ConnectionProfiles store = new ConnectionProfiles(settings);
        store.migrateSingleConnection();

        assertEquals(1, store.all().size());
        assertEquals("Laptop", store.autoConnectName());
        assertEquals("http://legacy:18083", store.all().get(0).endpoint());

        // A second call must not duplicate the profile.
        store.migrateSingleConnection();
        assertEquals(1, store.all().size());
    }

    @Test
    void nothingIsMigratedWithoutAPreviousConnection(@TempDir Path directory) {
        ConnectionProfiles store = new ConnectionProfiles(new ApplicationSettings(directory, false));

        store.migrateSingleConnection();

        assertTrue(store.all().isEmpty());
        assertFalse(store.selected().name().isBlank());
    }
}
