package com.remoteboxjava;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes VBoxManage commands locally or through an arbitrary command prefix.
 * <p>
 * Examples: {@code VBoxManage}, {@code ssh user@host VBoxManage}.
 */
public final class VBoxManageClient implements VirtualBoxClient {
    private static final Logger LOG = LogManager.getLogger(VBoxManageClient.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration LONG_OPERATION_TIMEOUT = Duration.ofMinutes(15);
    private static final Pattern MACHINE_PATTERN = Pattern.compile("^\"(.*)\"\\s+\\{([\\w-]+)}$");
    private static final String SCALE_FACTOR_KEY = "GUI/ScaleFactor";

    private final List<String> commandPrefix;

    public VBoxManageClient(String commandPrefix) {
        this.commandPrefix = splitCommand(commandPrefix == null || commandPrefix.isBlank()
                ? defaultCommand()
                : commandPrefix);
        if (this.commandPrefix.isEmpty()) {
            throw new IllegalArgumentException("A VBoxManage command must be configured.");
        }
    }

    public String version() throws VBoxException {
        return execute("--version").trim();
    }

    public List<VirtualMachine> listMachines() throws VBoxException {
        String output = execute("list", "vms");
        List<VirtualMachine> machines = new ArrayList<>();

        for (String line : output.lines().toList()) {
            Matcher matcher = MACHINE_PATTERN.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }

            String name = matcher.group(1);
            String id = matcher.group(2);
            machines.add(VirtualMachine.fromInfo(name, id, showVmInfo(id)));
        }
        return machines;
    }

    public void start(VirtualMachine machine) throws VBoxException {
        execute("startvm", machine.id(), "--type", "headless");
    }

    public void powerOff(VirtualMachine machine) throws VBoxException {
        execute("controlvm", machine.id(), "poweroff");
    }

    public void acpiShutdown(VirtualMachine machine) throws VBoxException {
        execute("controlvm", machine.id(), "acpipowerbutton");
    }

    public void saveState(VirtualMachine machine) throws VBoxException {
        execute("controlvm", machine.id(), "savestate");
    }

    public void pause(VirtualMachine machine) throws VBoxException {
        execute("controlvm", machine.id(), "pause");
    }

    public void resume(VirtualMachine machine) throws VBoxException {
        execute("controlvm", machine.id(), "resume");
    }

    public void reset(VirtualMachine machine) throws VBoxException {
        execute("controlvm", machine.id(), "reset");
    }

    public void discardSavedState(VirtualMachine machine) throws VBoxException {
        execute("discardstate", machine.id());
    }

    public void takeSnapshot(VirtualMachine machine, String name, String description) throws VBoxException {
        List<String> arguments = new ArrayList<>(List.of("snapshot", machine.id(), "take", name));
        if (description != null && !description.isBlank()) {
            arguments.add("--description");
            arguments.add(description);
        }
        execute(arguments);
    }

    public List<String> snapshots(VirtualMachine machine) throws VBoxException {
        String output = execute("snapshot", machine.id(), "list", "--machinereadable");
        List<String> snapshots = new ArrayList<>();
        for (String line : output.lines().toList()) {
            if (line.startsWith("SnapshotName")) {
                int equals = line.indexOf('=');
                if (equals >= 0) {
                    snapshots.add(unquote(line.substring(equals + 1)));
                }
            }
        }
        return snapshots;
    }

    public void restoreSnapshot(VirtualMachine machine, String snapshot) throws VBoxException {
        execute("snapshot", machine.id(), "restore", snapshot);
    }

    public void deleteSnapshot(VirtualMachine machine, String snapshot) throws VBoxException {
        execute("snapshot", machine.id(), "delete", snapshot);
    }

    public void createMachine(String name, String osType, int memoryMb, int cpuCount) throws VBoxException {
        createMachine(NewMachineSpec.basic(name, osType, memoryMb, cpuCount));
    }

    @Override
    public void createMachine(NewMachineSpec specification) throws VBoxException {
        boolean created = false;
        String diskFile = specification.name() + "." + specification.diskFormat().toLowerCase(Locale.ROOT);
        try {
            List<String> createVm = new ArrayList<>(List.of("createvm", "--name", specification.name(),
                    "--ostype", specification.osType(), "--register"));
            if (!specification.machineFolder().isBlank()) {
                createVm.add("--basefolder");
                createVm.add(specification.machineFolder());
            }
            execute(createVm);
            created = true;
            execute("modifyvm", specification.name(),
                    "--memory", Integer.toString(specification.memoryMb()),
                    "--cpus", Integer.toString(specification.cpuCount()),
                    "--clipboard", "bidirectional",
                    "--audio-driver", "none",
                    "--vrde", specification.vrdeEnabled() ? "on" : "off",
                    "--vrdeport", specification.vrdePort().isBlank() ? "3389" : specification.vrdePort());

            String startupDisk = "";
            if (specification.diskMode() == NewMachineSpec.DiskMode.NEW) {
                List<String> diskCommand = new ArrayList<>(List.of(
                        "createmedium", "disk", "--filename", diskFile,
                        "--format", specification.diskFormat(),
                        "--size", Integer.toString(specification.diskSizeMb())
                ));
                if (specification.fixedDisk()) {
                    diskCommand.add("--variant");
                    diskCommand.add("Fixed");
                }
                execute(diskCommand);
                startupDisk = diskFile;
            } else if (specification.diskMode() == NewMachineSpec.DiskMode.EXISTING) {
                startupDisk = specification.existingDiskPath();
            }

            boolean needsController = specification.diskMode() != NewMachineSpec.DiskMode.NONE
                    || !specification.installerIso().isBlank();
            if (needsController) {
                execute("storagectl", specification.name(), "--name", specification.controllerName(),
                        "--add", specification.controllerBus(), "--bootable", "on");
            }
            if (!startupDisk.isBlank()) {
                execute("storageattach", specification.name(), "--storagectl", specification.controllerName(),
                        "--port", Integer.toString(specification.diskPort()),
                        "--device", Integer.toString(specification.diskDevice()),
                        "--type", "hdd", "--medium", startupDisk);
            }
            if (!specification.installerIso().isBlank()) {
                execute("storageattach", specification.name(), "--storagectl", specification.controllerName(),
                        "--port", Integer.toString(specification.diskPort() + 1),
                        "--device", "0", "--type", "dvddrive", "--medium", specification.installerIso());
            }
        } catch (VBoxException exception) {
            if (created) {
                try {
                    execute("unregistervm", specification.name(), "--delete");
                } catch (VBoxException cleanupFailure) {
                    LOG.warn("Could not remove the half-created guest '{}'.", specification.name(), cleanupFailure);
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw new VBoxException("Could not create the bootable guest '" + specification.name()
                    + "': " + exception.getMessage(), exception);
        }
    }

    public void unregister(VirtualMachine machine, boolean deleteFiles) throws VBoxException {
        if (deleteFiles) {
            execute("unregistervm", machine.id(), "--delete");
        } else {
            execute("unregistervm", machine.id());
        }
    }

    @Override
    public MachineSettings machineSettings(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        return new MachineSettings(
                machine.name(),
                info.getOrDefault("description", machine.description()),
                info.getOrDefault("groups", machine.groups()),
                info.getOrDefault("ostype", machine.osType()),
                parseInt(info.get("memory"), machine.memoryMb()),
                parseInt(info.get("cpus"), machine.cpuCount()),
                parseInt(info.get("vram"), 16),
                "on".equalsIgnoreCase(info.getOrDefault("vrde", "")),
                info.getOrDefault("vrdeport", machine.vrdePort())
        );
    }

    @Override
    public void updateMachineSettings(VirtualMachine machine, MachineSettings settings) throws VBoxException {
        List<String> arguments = new ArrayList<>(List.of(
                "modifyvm", machine.id(),
                "--name", settings.name(),
                "--description", settings.description(),
                "--groups", settings.groups(),
                "--ostype", settings.osType(),
                "--memory", Integer.toString(settings.memoryMb()),
                "--cpus", Integer.toString(settings.cpuCount()),
                "--vram", Integer.toString(settings.videoMemoryMb()),
                "--vrde", settings.vrdeEnabled() ? "on" : "off"
        ));
        arguments.add("--vrdeport");
        arguments.add(settings.vrdePort().isBlank() ? "3389" : settings.vrdePort());
        execute(arguments);
    }

    @Override
    public MotherboardSettings motherboardSettings(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        String bootOrder = String.join(",",
                info.getOrDefault("boot1", "disk"), info.getOrDefault("boot2", "dvd"),
                info.getOrDefault("boot3", "none"), info.getOrDefault("boot4", "none"));
        String firmware = info.getOrDefault("firmware", "bios");
        return new MotherboardSettings(bootOrder, info.getOrDefault("chipset", "piix3"),
                info.getOrDefault("pointing", "ps2mouse"), firmware,
                !"bios".equalsIgnoreCase(firmware),
                "on".equalsIgnoreCase(info.getOrDefault("rtcuseutc", "off")),
                parseInt(info.get("cpuexecutioncap"), 100),
                "on".equalsIgnoreCase(info.getOrDefault("pae", "on")),
                "on".equalsIgnoreCase(info.getOrDefault("hwvirtex", "on")),
                "on".equalsIgnoreCase(info.getOrDefault("nestedpaging", "on")));
    }

    @Override
    public void updateMotherboardSettings(VirtualMachine machine, MotherboardSettings settings) throws VBoxException {
        String[] boot = settings.bootOrder().split(",", -1);
        String firmware = settings.efiEnabled()
                ? ("bios".equals(settings.firmware()) ? "efi" : settings.firmware())
                : "bios";
        execute("modifyvm", machine.id(),
                "--boot1", boot[0], "--boot2", boot[1], "--boot3", boot[2], "--boot4", boot[3],
                "--chipset", settings.chipset(),
                "--pointing", settings.pointingDevice(),
                "--firmware", firmware,
                "--rtcuseutc", settings.rtcUsesUtc() ? "on" : "off",
                "--cpuexecutioncap", Integer.toString(settings.executionCap()),
                "--pae", settings.paeEnabled() ? "on" : "off",
                "--hwvirtex", settings.hardwareVirtualizationEnabled() ? "on" : "off",
                "--nestedpaging", settings.nestedPagingEnabled() ? "on" : "off");
    }

    @Override
    public DisplaySettings displaySettings(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        return new DisplaySettings(info.getOrDefault("graphicscontroller", "vmsvga"),
                parseInt(info.get("monitorcount"), 1), scaleFactorPercent(machine.id()),
                "on".equalsIgnoreCase(info.getOrDefault("accelerate3d", "off")),
                "on".equalsIgnoreCase(info.getOrDefault("recording", "off")),
                info.getOrDefault("recordingfile", ""));
    }

    @Override
    public void updateDisplaySettings(VirtualMachine machine, DisplaySettings settings) throws VBoxException {
        List<String> command = new ArrayList<>(List.of("modifyvm", machine.id(),
                "--graphicscontroller", settings.graphicsController(),
                "--monitorcount", Integer.toString(settings.monitorCount()),
                "--accelerate3d", settings.acceleration3dEnabled() ? "on" : "off",
                "--recording", settings.recordingEnabled() ? "on" : "off"));
        if (!settings.recordingFile().isBlank()) {
            command.add("--recordingfile");
            command.add(settings.recordingFile());
        }
        execute(command);
        // Monitor scaling is not a modifyvm option; VirtualBox keeps it in extra data.
        execute("setextradata", machine.id(), SCALE_FACTOR_KEY, formatScaleFactor(settings.scaleFactor()));
    }

    private int scaleFactorPercent(String machineId) throws VBoxException {
        String output = execute("getextradata", machineId, SCALE_FACTOR_KEY).trim();
        String prefix = "Value:";
        return output.startsWith(prefix)
                ? parseScaleFactorPercent(output.substring(prefix.length()).trim())
                : 100;
    }

    @Override
    public String guestLog(VirtualMachine machine, int logIndex) throws VBoxException {
        return execute("showvminfo", machine.id(), "--log=" + Math.max(0, logIndex));
    }

    @Override
    public void cloneMachine(VirtualMachine machine, String name, boolean linked) throws VBoxException {
        List<String> arguments = new ArrayList<>(List.of(
                "clonevm", machine.id(), "--name", name, "--register"
        ));
        if (linked) {
            arguments.add("--options");
            arguments.add("link");
        }
        execute(arguments);
    }

    @Override
    public void importAppliance(String appliancePath) throws VBoxException {
        execute("import", appliancePath);
    }

    @Override
    public void exportAppliance(List<VirtualMachine> machines, String appliancePath) throws VBoxException {
        if (machines.isEmpty()) {
            throw new VBoxException("Select at least one guest to export.");
        }
        List<String> arguments = new ArrayList<>();
        arguments.add("export");
        for (VirtualMachine machine : machines) {
            arguments.add(machine.id());
        }
        arguments.add("--output");
        arguments.add(appliancePath);
        execute(arguments);
    }

    @Override
    public String serverInformation() throws VBoxException {
        return execute("list", "hostinfo").trim();
    }

    @Override
    public HostMemory hostMemory() throws VBoxException {
        return parseHostMemory(execute("list", "hostinfo"));
    }

    static HostMemory parseHostMemory(String hostInfo) throws VBoxException {
        long total = 0;
        long available = 0;
        for (String line : hostInfo.lines().toList()) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if ("memory size".equals(key)) {
                total = megabytes(line.substring(separator + 1));
            } else if ("memory available".equals(key)) {
                available = megabytes(line.substring(separator + 1));
            }
        }
        if (total <= 0) {
            throw new VBoxException("VirtualBox did not report the host memory size.");
        }
        return new HostMemory(total, available);
    }

    private static long megabytes(String value) {
        Matcher matcher = Pattern.compile("(\\d+)").matcher(value);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    @Override
    public List<VirtualMedia> virtualMedia() throws VBoxException {
        List<VirtualMedia> result = new ArrayList<>();
        collectMedia(result, execute("list", "hdds"), VirtualMedia.Type.HARD_DISK);
        collectMedia(result, execute("list", "dvds"), VirtualMedia.Type.OPTICAL);
        collectMedia(result, execute("list", "floppies"), VirtualMedia.Type.FLOPPY);
        return result;
    }

    @Override
    public String createVirtualDisk(String location, int sizeMb, String format, boolean fixed) throws VBoxException {
        String path = requireValue(location, "virtual disk path");
        if (sizeMb < 4) {
            throw new VBoxException("Virtual disk size must be at least 4 MB.");
        }
        String diskFormat = requireValue(format, "disk format").toUpperCase(Locale.ROOT);
        List<String> command = new ArrayList<>(List.of("createmedium", "disk", "--filename", path,
                "--format", diskFormat, "--size", Integer.toString(sizeMb)));
        if (fixed) {
            command.add("--variant");
            command.add("Fixed");
        }
        execute(command);
        return path;
    }

    @Override
    public void releaseVirtualDisk(String location) throws VBoxException {
        execute("closemedium", "disk", requireValue(location, "virtual disk location"));
    }

    @Override
    public void closeVirtualDisk(String location, boolean delete) throws VBoxException {
        List<String> command = new ArrayList<>(List.of("closemedium", "disk",
                requireValue(location, "virtual disk location")));
        if (delete) {
            command.add("--delete");
        }
        execute(command);
    }

    @Override
    public void resizeVirtualDisk(String location, int sizeMb) throws VBoxException {
        if (sizeMb < 4) {
            throw new VBoxException("Virtual disk size must be at least 4 MB.");
        }
        execute("modifymedium", "disk", requireValue(location, "virtual disk location"),
                "--resize", Integer.toString(sizeMb));
    }

    @Override
    public void compactVirtualDisk(String location) throws VBoxException {
        execute("modifymedium", "disk", requireValue(location, "virtual disk location"), "--compact");
    }

    @Override
    public void registerOpticalMedium(String location) throws VBoxException {
        execute("openmedium", "dvd", requireValue(location, "optical medium location"));
    }

    @Override
    public void unregisterOpticalMedium(String location) throws VBoxException {
        execute("closemedium", "dvd", requireValue(location, "optical medium location"));
    }

    @Override
    public void registerFloppyMedium(String location) throws VBoxException {
        execute("openmedium", "floppy", requireValue(location, "floppy medium location"));
    }

    @Override
    public void unregisterFloppyMedium(String location) throws VBoxException {
        execute("closemedium", "floppy", requireValue(location, "floppy medium location"));
    }

    @Override
    public String mediaInformation() throws VBoxException {
        return "Hard disks" + System.lineSeparator() + "=========="
                + System.lineSeparator() + execute("list", "hdds").trim()
                + System.lineSeparator() + System.lineSeparator()
                + "Optical disks" + System.lineSeparator() + "============"
                + System.lineSeparator() + execute("list", "dvds").trim()
                + System.lineSeparator() + System.lineSeparator()
                + "Floppy disks" + System.lineSeparator() + "============"
                + System.lineSeparator() + execute("list", "floppies").trim();
    }

    @Override
    public String hostNetworkInformation() throws VBoxException {
        return "Bridged interfaces" + System.lineSeparator() + "==================" + System.lineSeparator()
                + execute("list", "bridgedifs").trim() + System.lineSeparator() + System.lineSeparator()
                + "Host-only interfaces" + System.lineSeparator() + "====================" + System.lineSeparator()
                + execute("list", "hostonlyifs").trim() + System.lineSeparator() + System.lineSeparator()
                + "DHCP servers" + System.lineSeparator() + "============" + System.lineSeparator()
                + execute("list", "dhcpservers").trim();
    }

    @Override
    public List<HostNetworkInterface> hostNetworkInterfaces() throws VBoxException {
        return parseHostNetworkInterfaces(
                execute("list", "dhcpservers"),
                execute("list", "bridgedifs"),
                execute("list", "hostonlyifs"));
    }

    @Override
    public void createHostOnlyInterface() throws VBoxException {
        execute("hostonlyif", "create");
    }

    @Override
    public void removeHostOnlyInterface(String interfaceName) throws VBoxException {
        execute("hostonlyif", "remove", "--name", requireHostOnlyName(interfaceName));
    }

    @Override
    public void configureHostOnlyInterface(String interfaceName, String ipv4Address, String ipv4Mask,
                                           String ipv6Address, int ipv6PrefixLength) throws VBoxException {
        List<String> command = new ArrayList<>(List.of("hostonlyif", "ipconfig", requireHostOnlyName(interfaceName),
                "--ip", requireValue(ipv4Address, "IPv4 address"), "--netmask", requireValue(ipv4Mask, "IPv4 mask")));
        if (ipv6Address != null && !ipv6Address.isBlank()) {
            if (ipv6PrefixLength < 0 || ipv6PrefixLength > 128) {
                throw new VBoxException("IPv6 prefix length must be between 0 and 128.");
            }
            command.add("--ipv6");
            command.add(ipv6Address.trim());
            command.add("--netmasklengthv6");
            command.add(Integer.toString(ipv6PrefixLength));
        }
        execute(command);
    }

    @Override
    public void configureHostOnlyDhcp(String interfaceName, boolean enabled, String serverAddress,
                                      String lowerAddress, String upperAddress, String mask) throws VBoxException {
        String name = requireHostOnlyName(interfaceName);
        if (!enabled) {
            execute("dhcpserver", "modify", "--interface", name, "--enable", "off");
            return;
        }
        execute("dhcpserver", "modify", "--interface", name, "--enable", "on",
                "--server-ip", requireValue(serverAddress, "DHCP server address"),
                "--lowerip", requireValue(lowerAddress, "DHCP lower address"),
                "--upperip", requireValue(upperAddress, "DHCP upper address"),
                "--netmask", requireValue(mask, "DHCP mask"));
    }

    @Override
    public AudioSettings audioSettings(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        boolean enabled = "on".equalsIgnoreCase(info.getOrDefault("audio", "off"));
        String controller = info.getOrDefault("audiocontroller", "ac97");
        String driver = info.getOrDefault("audio-driver", "default");
        try {
            return new AudioSettings(enabled, controller, driver);
        } catch (IllegalArgumentException exception) {
            LOG.debug("VirtualBox reported an unknown audio configuration; falling back to AC97.", exception);
            return new AudioSettings(enabled, "ac97", "default");
        }
    }

    @Override
    public void updateAudioSettings(VirtualMachine machine, AudioSettings settings) throws VBoxException {
        execute("modifyvm", machine.id(),
                "--audio", settings.enabled() ? "on" : "off",
                "--audiocontroller", settings.controller(),
                "--audio-driver", settings.driver());
    }

    @Override
    public NetworkAdapterSettings networkAdapterSettings(VirtualMachine machine, int adapterIndex)
            throws VBoxException {
        validateNetworkAdapterIndex(adapterIndex);
        Map<String, String> info = showVmInfo(machine.id());
        String suffix = Integer.toString(adapterIndex);
        String attachmentType = info.getOrDefault("nic" + suffix, "none").toLowerCase(Locale.ROOT);
        String adapterName = switch (attachmentType) {
            case "bridged" -> info.getOrDefault("bridgeadapter" + suffix, "");
            case "hostonly" -> info.getOrDefault("hostonlyadapter" + suffix, "");
            case "intnet" -> info.getOrDefault("intnet" + suffix, "");
            default -> "";
        };
        return new NetworkAdapterSettings(!"none".equals(attachmentType), attachmentType, adapterName);
    }

    @Override
    public void updateNetworkAdapterSettings(VirtualMachine machine, int adapterIndex,
                                             NetworkAdapterSettings settings) throws VBoxException {
        validateNetworkAdapterIndex(adapterIndex);
        String suffix = Integer.toString(adapterIndex);
        if (!settings.enabled() || "none".equals(settings.attachmentType())) {
            execute("modifyvm", machine.id(), "--nic" + suffix, "none");
            return;
        }

        List<String> command = new ArrayList<>(List.of(
                "modifyvm", machine.id(), "--nic" + suffix, settings.attachmentType()
        ));
        switch (settings.attachmentType()) {
            case "bridged" -> {
                command.add("--bridgeadapter" + suffix);
                command.add(settings.adapterName());
            }
            case "hostonly" -> {
                if (settings.adapterName().isBlank()) {
                    throw new VBoxException("A host-only adapter name is required.");
                }
                command.add("--hostonlyadapter" + suffix);
                command.add(settings.adapterName());
            }
            case "intnet" -> {
                if (settings.adapterName().isBlank()) {
                    throw new VBoxException("An internal network name is required.");
                }
                command.add("--intnet" + suffix);
                command.add(settings.adapterName());
            }
            default -> {
                // NAT has no adapter-specific argument.
            }
        }
        execute(command);
    }

    private static void validateNetworkAdapterIndex(int adapterIndex) throws VBoxException {
        if (adapterIndex < 1 || adapterIndex > 8) {
            throw new VBoxException("VirtualBox network adapter index must be between 1 and 8.");
        }
    }

    @Override
    public List<NatPortForwardRule> natPortForwardRules(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        List<NatPortForwardRule> rules = new ArrayList<>();
        for (Map.Entry<String, String> entry : info.entrySet()) {
            if (!entry.getKey().matches("Forwarding\\(\\d+\\)")) {
                continue;
            }
            String[] values = entry.getValue().split(",", -1);
            if (values.length != 6) {
                continue;
            }
            try {
                rules.add(new NatPortForwardRule(values[0], values[1], values[2],
                        Integer.parseInt(values[3]), values[4], Integer.parseInt(values[5])));
            } catch (IllegalArgumentException exception) {
                LOG.warn("Skipping a malformed NAT port-forwarding rule.", exception);
            }
        }
        return rules;
    }

    @Override
    public void addNatPortForwardRule(VirtualMachine machine, NatPortForwardRule rule) throws VBoxException {
        if (!"nat".equals(networkAdapterSettings(machine).attachmentType())) {
            throw new VBoxException("Configure network adapter 1 for NAT before adding port-forwarding rules.");
        }
        execute("modifyvm", machine.id(), "--natpf1", rule.toVBoxManageRule());
    }

    @Override
    public void removeNatPortForwardRule(VirtualMachine machine, String ruleName) throws VBoxException {
        String normalizedName = ruleName == null ? "" : ruleName.trim();
        if (normalizedName.isBlank()) {
            throw new VBoxException("Select a NAT port-forwarding rule to remove.");
        }
        if (!"nat".equals(networkAdapterSettings(machine).attachmentType())) {
            throw new VBoxException("Configure network adapter 1 for NAT before removing port-forwarding rules.");
        }
        execute("modifyvm", machine.id(), "--natpf1", "delete", normalizedName);
    }

    @Override
    public List<SharedFolder> sharedFolders(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        List<SharedFolder> folders = new ArrayList<>();
        for (int index = 1; ; index++) {
            String name = info.get("SharedFolderNameMachineMapping" + index);
            if (name == null) {
                break;
            }
            folders.add(new SharedFolder(name, info.getOrDefault("SharedFolderPathMachineMapping" + index, ""),
                    "on".equalsIgnoreCase(info.getOrDefault("SharedFolderWritableMachineMapping" + index, "on")) == false,
                    "on".equalsIgnoreCase(info.getOrDefault("SharedFolderAutoMountMachineMapping" + index, "off"))));
        }
        return folders;
    }

    @Override
    public void addSharedFolder(VirtualMachine machine, String name, String hostPath, boolean readOnly,
                                boolean autoMount) throws VBoxException {
        if (name == null || name.isBlank() || hostPath == null || hostPath.isBlank()) {
            throw new VBoxException("A shared-folder name and host path are required.");
        }
        List<String> command = new ArrayList<>(List.of(
                "sharedfolder", "add", machine.id(),
                "--name", name.trim(),
                "--hostpath", hostPath.trim()
        ));
        if (readOnly) {
            command.add("--readonly");
        }
        if (autoMount) {
            command.add("--automount");
        }
        execute(command);
    }

    @Override
    public void removeSharedFolder(VirtualMachine machine, String name) throws VBoxException {
        if (name == null || name.isBlank()) {
            throw new VBoxException("Select a shared folder to remove.");
        }
        execute("sharedfolder", "remove", machine.id(), "--name", name.trim());
    }

    @Override
    public String sharedFolderInformation(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        StringBuilder folders = new StringBuilder();
        for (int index = 1; ; index++) {
            String name = info.get("SharedFolderNameMachineMapping" + index);
            if (name == null) {
                break;
            }
            String path = info.getOrDefault("SharedFolderPathMachineMapping" + index, "");
            folders.append(name).append("  →  ").append(path).append(System.lineSeparator());
        }
        return folders.isEmpty() ? "(No machine shared folders configured.)" : folders.toString().trim();
    }

    @Override
    public void attachOpticalMedium(VirtualMachine machine, String controllerName, int port, int device,
                                    String isoPath) throws VBoxException {
        String name = requireStorageControllerName(controllerName);
        validateStorageSlot(port, device);
        execute("storageattach", machine.id(), "--storagectl", name,
                "--port", Integer.toString(port), "--device", Integer.toString(device),
                "--type", "dvddrive",
                "--medium", isoPath == null || isoPath.isBlank() ? "emptydrive" : isoPath.trim());
    }

    @Override
    public List<StorageController> storageLayout(VirtualMachine machine) throws VBoxException {
        return parseStorageLayout(showVmInfo(machine.id()));
    }

    @Override
    public void updateStorageController(VirtualMachine machine, String currentName, StorageController updated)
            throws VBoxException {
        String name = requireStorageControllerName(currentName);
        List<String> command = new ArrayList<>(List.of("storagectl", machine.id(), "--name", name));
        if (!name.equals(updated.name())) {
            command.add("--rename");
            command.add(updated.name());
        }
        if (!updated.controllerType().isBlank()) {
            command.add("--controller");
            command.add(updated.controllerType());
        }
        if (updated.portCount() > 0) {
            command.add("--portcount");
            command.add(Integer.toString(updated.portCount()));
        }
        if (updated.useHostIoCache() != null) {
            command.add("--hostiocache");
            command.add(updated.useHostIoCache() ? "on" : "off");
        }
        execute(command);
    }

    static List<StorageController> parseStorageLayout(Map<String, String> info) {
        List<StorageController> controllers = new ArrayList<>();
        for (int index = 0; ; index++) {
            String name = info.get("storagecontrollername" + index);
            if (name == null) {
                break;
            }
            String controllerType = info.getOrDefault("storagecontrollertype" + index, "");
            String bus = StorageController.busForControllerType(controllerType);
            controllers.add(new StorageController(name, bus, controllerType,
                    parseInt(info.get("storagecontrollerportcount" + index), 1),
                    // showvminfo --machinereadable does not report the host I/O cache flag.
                    null,
                    "on".equalsIgnoreCase(info.getOrDefault("storagecontrollerbootable" + index, "off")),
                    parseAttachments(info, name, bus)));
        }
        return controllers;
    }

    private static List<StorageAttachment> parseAttachments(Map<String, String> info, String controllerName,
                                                            String bus) {
        Pattern slot = Pattern.compile("^" + Pattern.quote(controllerName) + "-(\\d+)-(\\d+)$");
        List<StorageAttachment> attachments = new ArrayList<>();
        for (Map.Entry<String, String> entry : info.entrySet()) {
            Matcher matcher = slot.matcher(entry.getKey());
            if (!matcher.matches()) {
                continue;
            }
            String medium = entry.getValue().trim();
            if (medium.isBlank() || "none".equalsIgnoreCase(medium)) {
                continue;
            }
            attachments.add(new StorageAttachment(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), deviceTypeFor(bus, medium), medium));
        }
        attachments.sort(Comparator.comparingInt(StorageAttachment::port)
                .thenComparingInt(StorageAttachment::device));
        return attachments;
    }

    /**
     * VBoxManage reports the attached medium but not its device type, so the type
     * is derived from the controller bus and the medium itself.
     */
    private static String deviceTypeFor(String bus, String medium) {
        if ("floppy".equals(bus)) {
            return "fdd";
        }
        String normalized = medium.toLowerCase(Locale.ROOT);
        boolean optical = "emptydrive".equals(normalized)
                || normalized.contains("host drive")
                || normalized.endsWith(".iso")
                || normalized.endsWith(".cdr")
                || normalized.endsWith(".dmg");
        return optical ? "dvd" : "hdd";
    }

    @Override
    public String storageInformation(VirtualMachine machine) throws VBoxException {
        StringBuilder result = new StringBuilder();
        for (StorageController controller : storageLayout(machine)) {
            result.append("Controller: ").append(controller.name())
                    .append("  (").append(controller.busDescription()).append(", ")
                    .append(controller.controllerType()).append(", ")
                    .append(controller.portCount()).append(" ports)")
                    .append(System.lineSeparator());
            for (StorageAttachment attachment : controller.attachments()) {
                result.append("  ").append(attachment.slot())
                        .append("  →  ").append(attachment.medium())
                        .append(System.lineSeparator());
            }
            result.append(System.lineSeparator());
        }
        return result.isEmpty() ? "(No storage controllers configured.)" : result.toString().trim();
    }

    @Override
    public void addStorageController(VirtualMachine machine, StorageControllerSpec controller) throws VBoxException {
        execute("storagectl", machine.id(), "--name", controller.name(), "--add", controller.bus());
    }

    @Override
    public void removeStorageController(VirtualMachine machine, String controllerName) throws VBoxException {
        String name = requireStorageControllerName(controllerName);
        execute("storagectl", machine.id(), "--name", name, "--remove");
    }

    @Override
    public void attachHardDisk(VirtualMachine machine, String controllerName, int port, int device,
                               String mediumPath) throws VBoxException {
        String name = requireStorageControllerName(controllerName);
        validateStorageSlot(port, device);
        String medium = mediumPath == null ? "" : mediumPath.trim();
        if (medium.isBlank()) {
            throw new VBoxException("Select an existing virtual hard-disk file to attach.");
        }
        execute("storageattach", machine.id(), "--storagectl", name,
                "--port", Integer.toString(port), "--device", Integer.toString(device),
                "--type", "hdd", "--medium", medium);
    }

    @Override
    public String createAndAttachHardDisk(VirtualMachine machine, String controllerName, int port, int device,
                                          String diskPath, int sizeMb, String format, boolean fixed) throws VBoxException {
        String name = requireStorageControllerName(controllerName);
        validateStorageSlot(port, device);
        String path = requireValue(diskPath, "virtual disk path");
        if (sizeMb < 4) {
            throw new VBoxException("Virtual disk size must be at least 4 MB.");
        }
        String diskFormat = requireValue(format, "disk format").toUpperCase(Locale.ROOT);
        if (!List.of("VDI", "VHD", "VMDK").contains(diskFormat)) {
            throw new VBoxException("Supported virtual disk formats are VDI, VHD, and VMDK.");
        }
        List<String> create = new ArrayList<>(List.of("createmedium", "disk", "--filename", path,
                "--format", diskFormat, "--size", Integer.toString(sizeMb)));
        if (fixed) {
            create.add("--variant");
            create.add("Fixed");
        }
        execute(create);
        try {
            attachHardDisk(machine, name, port, device, path);
            return path;
        } catch (VBoxException exception) {
            try {
                execute("closemedium", "disk", path, "--delete");
            } catch (VBoxException cleanupFailure) {
                LOG.warn("Could not delete the orphaned disk {}.", path, cleanupFailure);
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    @Override
    public void detachStorageMedium(VirtualMachine machine, String controllerName, int port, int device)
            throws VBoxException {
        String name = requireStorageControllerName(controllerName);
        validateStorageSlot(port, device);
        execute("storageattach", machine.id(), "--storagectl", name,
                "--port", Integer.toString(port), "--device", Integer.toString(device),
                "--medium", "none");
    }

    private static String requireStorageControllerName(String controllerName) throws VBoxException {
        String name = controllerName == null ? "" : controllerName.trim();
        if (name.isBlank()) {
            throw new VBoxException("Select a storage controller.");
        }
        return name;
    }

    private static String requireHostOnlyName(String interfaceName) throws VBoxException {
        return requireValue(interfaceName, "host-only interface name");
    }

    private static String requireValue(String value, String label) throws VBoxException {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new VBoxException("A " + label + " is required.");
        }
        return normalized;
    }

    private static void validateStorageSlot(int port, int device) throws VBoxException {
        if (port < 0 || device < 0) {
            throw new VBoxException("Storage port and device numbers must be zero or greater.");
        }
    }

    @Override
    public SerialPortSettings serialPortSettings(VirtualMachine machine, int portIndex) throws VBoxException {
        validateSerialPortIndex(portIndex);
        Map<String, String> info = showVmInfo(machine.id());
        String value = info.getOrDefault("uart" + portIndex, "off").trim();
        if ("off".equalsIgnoreCase(value)) {
            return new SerialPortSettings(false, "0x3f8", 4, "disconnected");
        }
        String[] fields = value.split(",", -1);
        if (fields.length != 2) {
            throw new VBoxException("VirtualBox returned an invalid UART " + portIndex + " configuration.");
        }
        try {
            return new SerialPortSettings(true, fields[0], Integer.parseInt(fields[1]),
                    info.getOrDefault("uartmode" + portIndex, "disconnected"));
        } catch (IllegalArgumentException exception) {
            throw new VBoxException("VirtualBox returned an unsupported UART " + portIndex + " configuration.", exception);
        }
    }

    @Override
    public void updateSerialPortSettings(VirtualMachine machine, int portIndex, SerialPortSettings settings)
            throws VBoxException {
        validateSerialPortIndex(portIndex);
        String option = "--uart" + portIndex;
        if (!settings.enabled()) {
            execute("modifyvm", machine.id(), option, "off");
            return;
        }
        execute("modifyvm", machine.id(), option, settings.ioBase(), Integer.toString(settings.irq()),
                "--uartmode" + portIndex, settings.mode());
    }

    @Override
    public ParallelPortSettings parallelPortSettings(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        String value = info.getOrDefault("parallel1", "off").trim();
        if ("off".equalsIgnoreCase(value)) {
            return new ParallelPortSettings(false, "0x378", 7);
        }
        String[] fields = value.split(",", -1);
        if (fields.length != 2) {
            throw new VBoxException("VirtualBox returned an invalid parallel-port configuration.");
        }
        try {
            return new ParallelPortSettings(true, fields[0], Integer.parseInt(fields[1]));
        } catch (IllegalArgumentException exception) {
            throw new VBoxException("VirtualBox returned an unsupported parallel-port configuration.", exception);
        }
    }

    @Override
    public void updateParallelPortSettings(VirtualMachine machine, ParallelPortSettings settings)
            throws VBoxException {
        if (!settings.enabled()) {
            execute("modifyvm", machine.id(), "--parallel1", "off");
            return;
        }
        execute("modifyvm", machine.id(), "--parallel1", settings.ioBase(), Integer.toString(settings.irq()));
    }

    private static void validateSerialPortIndex(int portIndex) throws VBoxException {
        if (portIndex < 1 || portIndex > 2) {
            throw new VBoxException("VirtualBox serial-port index must be 1 or 2.");
        }
    }

    @Override
    public UsbControllerSettings usbControllerSettings(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        if (!"on".equalsIgnoreCase(info.getOrDefault("usb", "off"))) {
            return new UsbControllerSettings(false, "none");
        }
        if ("on".equalsIgnoreCase(info.getOrDefault("usbxhci", "off"))) {
            return new UsbControllerSettings(true, "xhci");
        }
        if ("on".equalsIgnoreCase(info.getOrDefault("usbehci", "off"))) {
            return new UsbControllerSettings(true, "ehci");
        }
        return new UsbControllerSettings(true, "ohci");
    }

    @Override
    public void updateUsbControllerSettings(VirtualMachine machine, UsbControllerSettings settings)
            throws VBoxException {
        boolean enabled = settings.enabled();
        execute("modifyvm", machine.id(),
                "--usb", enabled ? "on" : "off",
                "--usbehci", enabled && "ehci".equals(settings.controller()) ? "on" : "off",
                "--usbxhci", enabled && "xhci".equals(settings.controller()) ? "on" : "off");
    }

    @Override
    public List<UsbDeviceFilter> usbDeviceFilters(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        List<UsbDeviceFilter> filters = new ArrayList<>();
        for (int index = 0; ; index++) {
            String suffix = Integer.toString(index);
            String name = info.get("USBFilterName" + suffix);
            if (name == null) {
                break;
            }
            try {
                filters.add(new UsbDeviceFilter(name,
                        "on".equalsIgnoreCase(info.getOrDefault("USBFilterActive" + suffix, "on")),
                        info.getOrDefault("USBFilterVendorId" + suffix, ""),
                        info.getOrDefault("USBFilterProductId" + suffix, "")));
            } catch (IllegalArgumentException exception) {
                LOG.warn("Skipping a malformed USB device filter.", exception);
            }
        }
        return filters;
    }

    @Override
    public void addUsbDeviceFilter(VirtualMachine machine, UsbDeviceFilter filter) throws VBoxException {
        List<String> command = new ArrayList<>(List.of("usbfilter", "add", "0", "--target", machine.id(),
                "--name", filter.name(), "--active", filter.active() ? "yes" : "no"));
        if (!filter.vendorId().isBlank()) {
            command.add("--vendorid");
            command.add(filter.vendorId());
        }
        if (!filter.productId().isBlank()) {
            command.add("--productid");
            command.add(filter.productId());
        }
        execute(command);
    }

    @Override
    public void removeUsbDeviceFilter(VirtualMachine machine, String filterName) throws VBoxException {
        String name = filterName == null ? "" : filterName.trim();
        if (name.isBlank()) {
            throw new VBoxException("Select a USB device filter to remove.");
        }
        Map<String, String> info = showVmInfo(machine.id());
        for (int index = 0; ; index++) {
            String candidate = info.get("USBFilterName" + index);
            if (candidate == null) {
                break;
            }
            if (name.equals(candidate)) {
                execute("usbfilter", "remove", Integer.toString(index), "--target", machine.id());
                return;
            }
        }
        throw new VBoxException("The USB device filter '" + name + "' no longer exists.");
    }

    @Override
    public GuestIntegrationSettings guestIntegrationSettings(VirtualMachine machine) throws VBoxException {
        Map<String, String> info = showVmInfo(machine.id());
        try {
            return new GuestIntegrationSettings(info.getOrDefault("clipboard", "disabled"),
                    info.getOrDefault("draganddrop", "disabled"));
        } catch (IllegalArgumentException exception) {
            throw new VBoxException("VirtualBox returned unsupported guest integration settings.", exception);
        }
    }

    @Override
    public void updateGuestIntegrationSettings(VirtualMachine machine, GuestIntegrationSettings settings)
            throws VBoxException {
        execute("modifyvm", machine.id(),
                "--clipboard", settings.clipboardMode(),
                "--draganddrop", settings.dragAndDropMode());
    }

    @Override
    public void sendCtrlAltDelete(VirtualMachine machine) throws VBoxException {
        execute("controlvm", machine.id(), "keyboardputscancode",
                "1d", "38", "53", "d3", "b8", "9d");
    }

    @Override
    public byte[] screenshotPng(VirtualMachine machine) throws VBoxException {
        Path screenshot = null;
        try {
            screenshot = Files.createTempFile("remotebox-", ".png");
            execute("controlvm", machine.id(), "screenshotpng", screenshot.toString());
            return Files.readAllBytes(screenshot);
        } catch (IOException exception) {
            throw new VBoxException("Could not read the guest screenshot.", exception);
        } finally {
            if (screenshot != null) {
                try {
                    Files.deleteIfExists(screenshot);
                } catch (IOException exception) {
                    LOG.debug("Could not delete the temporary screenshot file.", exception);
                }
            }
        }
    }

    @Override
    public String showDisplay(VirtualMachine machine) throws VBoxException {
        execute("startvm", machine.id(), "--type", "separate");
        return "VBoxManage startvm " + machine.id() + " --type separate";
    }

    private static void collectMedia(List<VirtualMedia> result, String output, VirtualMedia.Type type) {
        for (Map<String, String> block : parseBlocks(output)) {
            String location = block.getOrDefault("Location", "");
            String id = block.getOrDefault("UUID", "");
            if (location.isBlank() && id.isBlank()) {
                continue;
            }
            long capacityMb = parseCapacityMb(block.get("Capacity"));
            result.add(new VirtualMedia(id, location, type, block.getOrDefault("Format", ""),
                    capacityMb, List.of()));
        }
    }

    static List<HostNetworkInterface> parseHostNetworkInterfaces(String dhcpServers, String bridgedInterfaces,
                                                                 String hostOnlyInterfaces) {
        Map<String, Boolean> dhcpByInterface = new LinkedHashMap<>();
        for (Map<String, String> block : parseBlocks(dhcpServers)) {
            String interfaceName = dhcpInterfaceName(block.getOrDefault("NetworkName", ""));
            if (!interfaceName.isBlank()) {
                dhcpByInterface.put(interfaceName, isEnabled(block.get("Enabled")));
            }
        }

        List<HostNetworkInterface> result = new ArrayList<>();
        for (Map<String, String> block : parseBlocks(bridgedInterfaces)) {
            result.add(toHostNetworkInterface(block, "bridged", false));
        }
        for (Map<String, String> block : parseBlocks(hostOnlyInterfaces)) {
            String name = block.getOrDefault("Name", "").trim();
            result.add(toHostNetworkInterface(block, "host-only", dhcpByInterface.getOrDefault(name, false)));
        }
        return result;
    }

    private static HostNetworkInterface toHostNetworkInterface(Map<String, String> block, String type,
                                                                boolean dhcpEnabled) {
        return new HostNetworkInterface(block.getOrDefault("Name", ""), type,
                block.getOrDefault("IPAddress", ""), block.getOrDefault("NetworkMask", ""),
                block.getOrDefault("IPV6Address", ""), parseInt(block.get("IPV6NetworkMaskPrefixLength"), 0),
                dhcpEnabled);
    }

    private static String dhcpInterfaceName(String networkName) {
        String normalized = networkName == null ? "" : networkName.trim();
        String prefix = "HostInterfaceNetworking-";
        return normalized.regionMatches(true, 0, prefix, 0, prefix.length())
                ? normalized.substring(prefix.length())
                : normalized;
    }

    private static boolean isEnabled(String value) {
        String normalized = value == null ? "" : value.trim();
        return "yes".equalsIgnoreCase(normalized) || "on".equalsIgnoreCase(normalized)
                || "true".equalsIgnoreCase(normalized) || "enabled".equalsIgnoreCase(normalized);
    }

    static long parseCapacityMb(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(bytes?|kbytes?|mbytes?|gbytes?|tbytes?)?$",
                        Pattern.CASE_INSENSITIVE)
                .matcher(value.trim());
        if (!matcher.matches()) {
            return 0;
        }
        try {
            double amount = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2) == null ? "bytes" : matcher.group(2).toLowerCase(Locale.ROOT);
            double bytes = switch (unit) {
                case "byte", "bytes" -> amount;
                case "kbyte", "kbytes" -> amount * 1024;
                case "mbyte", "mbytes" -> amount * 1024 * 1024;
                case "gbyte", "gbytes" -> amount * 1024 * 1024 * 1024;
                case "tbyte", "tbytes" -> amount * 1024 * 1024 * 1024 * 1024;
                default -> 0;
            };
            return (long) (bytes / (1024 * 1024));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static List<Map<String, String>> parseBlocks(String output) {
        List<Map<String, String>> blocks = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    blocks.add(current);
                    current = new LinkedHashMap<>();
                }
                continue;
            }
            int separator = line.indexOf(':');
            if (separator > 0) {
                current.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        if (!current.isEmpty()) {
            blocks.add(current);
        }
        return blocks;
    }

    private Map<String, String> showVmInfo(String machineId) throws VBoxException {
        return parseMachineInfo(execute("showvminfo", machineId, "--machinereadable"));
    }

    static Map<String, String> parseMachineInfo(String output) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            // Storage attachment keys such as "SATA-0-0" are quoted by VBoxManage.
            values.put(unquote(line.substring(0, separator)), unquote(line.substring(separator + 1)));
        }
        return values;
    }

    private String execute(String... arguments) throws VBoxException {
        return execute(List.of(arguments));
    }

    private String execute(List<String> arguments) throws VBoxException {
        List<String> command = new ArrayList<>(commandPrefix);
        command.addAll(arguments);

        long started = System.nanoTime();
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("remotebox-vboxmanage-", ".log");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();

            Duration timeout = timeoutFor(arguments);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                LOG.warn("{} timed out after {} seconds.", String.join(" ", command), timeout.toSeconds());
                throw new VBoxException("VirtualBox command timed out after " + timeout.toSeconds() + " seconds.");
            }

            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            LOG.debug("{} exited with {} after {} ms.", String.join(" ", command), process.exitValue(),
                    (System.nanoTime() - started) / 1_000_000L);
            if (process.exitValue() != 0) {
                String message = output.isBlank()
                        ? "VirtualBox returned exit code " + process.exitValue() + "."
                        : output.trim();
                LOG.warn("{} failed: {}", String.join(" ", command), message);
                throw new VBoxException(message);
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOG.warn("{} was interrupted.", String.join(" ", command));
            throw new VBoxException("VirtualBox command was interrupted.", exception);
        } catch (IOException exception) {
            LOG.warn("Could not run {}.", String.join(" ", command), exception);
            throw new VBoxException("Could not run VirtualBox command: " + String.join(" ", command), exception);
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

    private static Duration timeoutFor(List<String> arguments) {
        if (arguments.isEmpty()) {
            return COMMAND_TIMEOUT;
        }
        return switch (arguments.get(0).toLowerCase(Locale.ROOT)) {
            case "clonevm", "import", "export" -> LONG_OPERATION_TIMEOUT;
            default -> COMMAND_TIMEOUT;
        };
    }

    private static String defaultCommand() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "VBoxManage.exe"
                : "VBoxManage";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * VBoxManage represents monitor scaling as a decimal multiplier (for example,
     * {@code 1.50}), while the UI represents it as a human-readable percentage.
     */
    private static int parseScaleFactorPercent(String value) {
        if (value == null || value.isBlank()) {
            return 100;
        }
        try {
            double multiplier = Double.parseDouble(value);
            int percentage = (int) Math.round(multiplier * 100);
            return percentage >= 10 && percentage <= 200 ? percentage : 100;
        } catch (NumberFormatException ignored) {
            return 100;
        }
    }

    private static String formatScaleFactor(int percentage) {
        return String.format(Locale.ROOT, "%.2f", percentage / 100d);
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
    }

    static List<String> splitCommand(String command) {
        List<String> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^\\s]+)").matcher(command);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                result.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                result.add(matcher.group(2));
            } else {
                result.add(matcher.group(3));
            }
        }
        return result;
    }

    public static final class VBoxException extends Exception {
        public VBoxException(String message) {
            super(message);
        }

        public VBoxException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
