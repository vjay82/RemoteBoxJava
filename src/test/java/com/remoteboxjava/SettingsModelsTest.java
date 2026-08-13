package com.remoteboxjava;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsModelsTest {
    @Test
    void motherboardSettingsNormalizesAndRetainsValidValues() {
        MotherboardSettings settings = new MotherboardSettings(
                " Disk, DVD, None, NET ", "ICH9", "USBTablet", "EFI64",
                false, true, 85, true, true, false);

        assertEquals("disk,dvd,none,net", settings.bootOrder());
        assertEquals("ich9", settings.chipset());
        assertEquals("usbtablet", settings.pointingDevice());
        assertEquals("efi64", settings.firmware());
        assertEquals(85, settings.executionCap());
        assertTrue(settings.rtcUsesUtc());
    }

    @Test
    void motherboardSettingsRejectsInvalidBootOrderAndExecutionCap() {
        assertThrows(IllegalArgumentException.class, () -> new MotherboardSettings(
                "disk,dvd,none", "piix3", "ps2mouse", "bios",
                false, false, 100, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> new MotherboardSettings(
                "disk,dvd,none,none", "piix3", "ps2mouse", "bios",
                false, false, 101, true, true, true));
    }

    @Test
    void displaySettingsAcceptsSupportedConfiguration() {
        DisplaySettings settings = new DisplaySettings("VBoxSVGA", 2, 150, true, true, "capture.webm");

        assertEquals("vboxsvga", settings.graphicsController());
        assertEquals(2, settings.monitorCount());
        assertEquals(150, settings.scaleFactor());
        assertTrue(settings.recordingEnabled());
    }

    @Test
    void displaySettingsRejectsUnsupportedControllerAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new DisplaySettings("invalid", 1, 100, false, false, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new DisplaySettings("vmsvga", 0, 100, false, false, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new DisplaySettings("vmsvga", 1, 250, false, false, ""));
    }

    @Test
    void commandPrefixParserPreservesQuotedArguments() {
        assertEquals(List.of("ssh", "user@example.test", "VBoxManage"),
                VBoxManageClient.splitCommand("ssh user@example.test VBoxManage"));
        assertEquals(List.of("C:\\Program Files\\VirtualBox\\VBoxManage.exe", "--nologo"),
                VBoxManageClient.splitCommand("\"C:\\Program Files\\VirtualBox\\VBoxManage.exe\" --nologo"));
    }

    @Test
    void hostNetworkParserMatchesVirtualBoxDhcpNetworkNames() {
        String dhcpServers = """
                NetworkName:    HostInterfaceNetworking-VirtualBox Host-Only Ethernet Adapter
                Enabled:        Yes
                """;
        String bridgedInterfaces = """
                Name:            Ethernet
                IPAddress:       192.168.1.20
                NetworkMask:     255.255.255.0
                """;
        String hostOnlyInterfaces = """
                Name:            VirtualBox Host-Only Ethernet Adapter
                IPAddress:       192.168.56.1
                NetworkMask:     255.255.255.0
                IPV6Address:     fe80::1
                IPV6NetworkMaskPrefixLength: 64
                """;

        List<HostNetworkInterface> interfaces = VBoxManageClient.parseHostNetworkInterfaces(
                dhcpServers, bridgedInterfaces, hostOnlyInterfaces);

        assertEquals(2, interfaces.size());
        assertFalse(interfaces.get(0).dhcpEnabled());
        assertTrue(interfaces.get(1).dhcpEnabled());
        assertEquals(64, interfaces.get(1).ipv6PrefixLength());
    }

    @Test
    void virtualMachineStartEligibilitySupportsWebServicePoweredOffState() {
        VirtualMachine poweredOff = new VirtualMachine(
                "Powered-off", "id-powered-off", "PoweredOff", "Windows10_64", 4096, 2, "/",
                "", "");
        VirtualMachine saved = new VirtualMachine(
                "Saved", "id-saved", "Saved", "Windows10_64", 4096, 2, "/",
                "", "");

        assertTrue(poweredOff.isPoweredOff());
        assertTrue(poweredOff.canStart());
        assertTrue(saved.canStart());
    }

    @Test
    void mediaCapacityParserHandlesVirtualBoxUnits() {
        assertEquals(1_024, VBoxManageClient.parseCapacityMb("1073741824 bytes"));
        assertEquals(20_480, VBoxManageClient.parseCapacityMb("20480 MBytes"));
        assertEquals(1_536, VBoxManageClient.parseCapacityMb("1.5 GBytes"));
        assertEquals(0, VBoxManageClient.parseCapacityMb("unknown"));
        assertEquals(0, VBoxManageClient.parseCapacityMb(null));
    }

    @Test
    void storageLayoutParserReadsQuotedAttachmentKeys() {        String machineReadable = """
                storagecontrollername0="IDE"
                storagecontrollertype0="PIIX4"
                storagecontrollerportcount0="2"
                storagecontrollerbootable0="on"
                storagecontrollername1="SATA"
                storagecontrollertype1="IntelAhci"
                storagecontrollerportcount1="2"
                storagecontrollerbootable1="on"
                "IDE-0-0"="emptydrive"
                "IDE-0-1"="none"
                "SATA-0-0"="C:\\VMs\\Windows10.vdi"
                """;

        List<StorageController> controllers =
                VBoxManageClient.parseStorageLayout(VBoxManageClient.parseMachineInfo(machineReadable));

        assertEquals(2, controllers.size());
        StorageController ide = controllers.get(0);
        assertEquals("ide", ide.bus());
        assertEquals(1, ide.attachments().size());
        assertEquals("dvd", ide.attachments().get(0).deviceType());
        assertTrue(ide.attachments().get(0).empty());

        StorageController sata = controllers.get(1);
        assertEquals("sata", sata.bus());
        assertEquals("hdd", sata.attachments().get(0).deviceType());
        assertEquals("Windows10.vdi", sata.attachments().get(0).displayName());
        assertEquals("Port 0, Device 0", sata.attachments().get(0).slot());
    }

    @Test
    void hostMemoryIsParsedFromHostInfo() throws Exception {
        String hostInfo = """
                Host Information:

                Host time: 2026-08-13T09:00:00.000000000
                Processor count: 16
                Processor core count: 8
                Memory size: 32605 MByte
                Memory available: 18402 MByte
                Operating system: Windows 11
                """;

        HostMemory memory = VBoxManageClient.parseHostMemory(hostInfo);

        assertEquals(32_605, memory.totalMb());
        assertEquals(18_402, memory.availableMb());
        assertEquals(14_203, memory.usedMb());
        assertEquals(44, memory.usedPercent());
        assertEquals("Server Memory: 13.9 GB of 31.8 GB used (18.0 GB free)", memory.describe());
    }

    @Test
    void hostMemoryReportsUnavailableSizeAsAnError() {
        assertThrows(VBoxManageClient.VBoxException.class,
                () -> VBoxManageClient.parseHostMemory("Host Information:\nProcessor count: 8\n"));
    }

    @Test
    void hostMemoryNeverReportsMoreUsedThanInstalled() {
        HostMemory memory = new HostMemory(4_096, 8_192);

        assertEquals(4_096, memory.availableMb());
        assertEquals(0, memory.usedMb());
        assertEquals(0, memory.usedPercent());
    }
}
