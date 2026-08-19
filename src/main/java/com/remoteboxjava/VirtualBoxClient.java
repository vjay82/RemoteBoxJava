package com.remoteboxjava;

import com.remoteboxjava.VBoxManageClient.VBoxException;

import java.util.List;
import java.util.function.Consumer;

/**
 * Common abstraction for local VBoxManage and RemoteBox-compatible web-service connections.
 */
public interface VirtualBoxClient extends AutoCloseable {
    String version() throws VBoxException;

    List<VirtualMachine> listMachines() throws VBoxException;

    /**
     * Loads the guest list and reports intermediate results as they become
     * available, so the UI can render before every per-guest field has arrived.
     * The consumer is called from the calling thread.
     */
    default List<VirtualMachine> listMachines(Consumer<List<VirtualMachine>> progress) throws VBoxException {
        List<VirtualMachine> machines = listMachines();
        progress.accept(machines);
        return machines;
    }

    void start(VirtualMachine machine) throws VBoxException;

    void powerOff(VirtualMachine machine) throws VBoxException;

    void acpiShutdown(VirtualMachine machine) throws VBoxException;

    void saveState(VirtualMachine machine) throws VBoxException;

    void pause(VirtualMachine machine) throws VBoxException;

    void resume(VirtualMachine machine) throws VBoxException;

    void reset(VirtualMachine machine) throws VBoxException;

    void discardSavedState(VirtualMachine machine) throws VBoxException;

    void takeSnapshot(VirtualMachine machine, String name, String description) throws VBoxException;

    List<String> snapshots(VirtualMachine machine) throws VBoxException;

    void restoreSnapshot(VirtualMachine machine, String snapshot) throws VBoxException;

    void deleteSnapshot(VirtualMachine machine, String snapshot) throws VBoxException;

    /**
     * The snapshot forest of a guest. Transports that can only report snapshot
     * names fall back to a flat list without timestamps.
     */
    default Snapshot.Tree snapshotTree(VirtualMachine machine) throws VBoxException {
        return new Snapshot.Tree(snapshots(machine).stream()
                .map(name -> new Snapshot(name, name, "", 0L, false, false, List.of()))
                .toList(), false);
    }

    default void updateSnapshot(VirtualMachine machine, String snapshotId, String name, String description)
            throws VBoxException {
        throw unsupported("edit snapshot properties");
    }

    default void cloneFromSnapshot(VirtualMachine machine, String snapshotId, String name, boolean linked)
            throws VBoxException {
        throw unsupported("clone a guest from a snapshot");
    }

    void createMachine(String name, String osType, int memoryMb, int cpuCount) throws VBoxException;

    /**
     * Creates a guest with a startup disk, optional installer image, and
     * remote-display configuration. Implementations that support the complete
     * RemoteBox creation workflow override this method.
     */
    default void createMachine(NewMachineSpec specification) throws VBoxException {
        createMachine(specification.name(), specification.osType(), specification.memoryMb(), specification.cpuCount());
    }

    void unregister(VirtualMachine machine, boolean deleteFiles) throws VBoxException;

    default MachineSettings machineSettings(VirtualMachine machine) throws VBoxException {
        return MachineSettings.from(machine);
    }

    default void updateMachineSettings(VirtualMachine machine, MachineSettings settings) throws VBoxException {
        throw unsupported("update guest settings");
    }

    default MotherboardSettings motherboardSettings(VirtualMachine machine) throws VBoxException {
        throw unsupported("read motherboard settings");
    }

    default void updateMotherboardSettings(VirtualMachine machine, MotherboardSettings settings) throws VBoxException {
        throw unsupported("update motherboard settings");
    }

    default DisplaySettings displaySettings(VirtualMachine machine) throws VBoxException {
        throw unsupported("read display settings");
    }

    default void updateDisplaySettings(VirtualMachine machine, DisplaySettings settings) throws VBoxException {
        throw unsupported("update display settings");
    }

    default String guestLog(VirtualMachine machine, int logIndex) throws VBoxException {
        throw unsupported("read guest logs");
    }

    default void cloneMachine(VirtualMachine machine, String name, boolean linked) throws VBoxException {
        throw unsupported("clone guests");
    }

    default void importAppliance(String appliancePath) throws VBoxException {
        throw unsupported("import appliances");
    }

    default void exportAppliance(List<VirtualMachine> machines, String appliancePath) throws VBoxException {
        throw unsupported("export appliances");
    }

    default String serverInformation() throws VBoxException {
        return "VirtualBox version: " + version();
    }

    /**
     * Physical memory of the VirtualBox host, refreshed alongside the guest list.
     */
    default HostMemory hostMemory() throws VBoxException {
        throw unsupported("read the host memory usage");
    }

    default String mediaInformation() throws VBoxException {
        throw unsupported("open the Virtual Media Manager");
    }

    default List<VirtualMedia> virtualMedia() throws VBoxException {
        throw unsupported("list virtual media");
    }

    default String createVirtualDisk(String location, int sizeMb, String format, boolean fixed) throws VBoxException {
        throw unsupported("create virtual disks");
    }

    default void releaseVirtualDisk(String location) throws VBoxException {
        throw unsupported("release virtual disks");
    }

    default void closeVirtualDisk(String location, boolean delete) throws VBoxException {
        throw unsupported("close virtual disks");
    }

    default void resizeVirtualDisk(String location, int sizeMb) throws VBoxException {
        throw unsupported("resize virtual disks");
    }

    default void compactVirtualDisk(String location) throws VBoxException {
        throw unsupported("compact virtual disks");
    }

    default void registerOpticalMedium(String location) throws VBoxException {
        throw unsupported("register optical media");
    }

    default void unregisterOpticalMedium(String location) throws VBoxException {
        throw unsupported("unregister optical media");
    }

    default void registerFloppyMedium(String location) throws VBoxException {
        throw unsupported("register floppy media");
    }

    default void unregisterFloppyMedium(String location) throws VBoxException {
        throw unsupported("unregister floppy media");
    }

    default String hostNetworkInformation() throws VBoxException {
        throw unsupported("open the Host Network Manager");
    }

    default List<HostNetworkInterface> hostNetworkInterfaces() throws VBoxException {
        throw unsupported("list host network interfaces");
    }

    default void createHostOnlyInterface() throws VBoxException {
        throw unsupported("create host-only interfaces");
    }

    default void removeHostOnlyInterface(String interfaceName) throws VBoxException {
        throw unsupported("remove host-only interfaces");
    }

    default void configureHostOnlyInterface(String interfaceName, String ipv4Address, String ipv4Mask,
                                            String ipv6Address, int ipv6PrefixLength) throws VBoxException {
        throw unsupported("configure host-only interfaces");
    }

    default void configureHostOnlyDhcp(String interfaceName, boolean enabled, String serverAddress,
                                       String lowerAddress, String upperAddress, String mask) throws VBoxException {
        throw unsupported("configure host-only DHCP");
    }

    default AudioSettings audioSettings(VirtualMachine machine) throws VBoxException {
        throw unsupported("read audio settings");
    }

    default void updateAudioSettings(VirtualMachine machine, AudioSettings settings) throws VBoxException {
        throw unsupported("update audio settings");
    }

    default NetworkAdapterSettings networkAdapterSettings(VirtualMachine machine) throws VBoxException {
        return networkAdapterSettings(machine, 1);
    }

    default NetworkAdapterSettings networkAdapterSettings(VirtualMachine machine, int adapterIndex)
            throws VBoxException {
        throw unsupported("read network adapter settings");
    }

    default void updateNetworkAdapterSettings(VirtualMachine machine, NetworkAdapterSettings settings)
            throws VBoxException {
        updateNetworkAdapterSettings(machine, 1, settings);
    }

    default void updateNetworkAdapterSettings(VirtualMachine machine, int adapterIndex,
                                              NetworkAdapterSettings settings) throws VBoxException {
        throw unsupported("update network adapter settings");
    }

    default List<NatPortForwardRule> natPortForwardRules(VirtualMachine machine) throws VBoxException {
        throw unsupported("read NAT port-forwarding rules");
    }

    default void addNatPortForwardRule(VirtualMachine machine, NatPortForwardRule rule) throws VBoxException {
        throw unsupported("add NAT port-forwarding rules");
    }

    default void removeNatPortForwardRule(VirtualMachine machine, String ruleName) throws VBoxException {
        throw unsupported("remove NAT port-forwarding rules");
    }

    default List<SharedFolder> sharedFolders(VirtualMachine machine) throws VBoxException {
        throw unsupported("read shared folders");
    }

    default void addSharedFolder(VirtualMachine machine, String name, String hostPath, boolean readOnly,
                                 boolean autoMount) throws VBoxException {
        throw unsupported("add shared folders");
    }

    default void removeSharedFolder(VirtualMachine machine, String name) throws VBoxException {
        throw unsupported("remove shared folders");
    }

    default String sharedFolderInformation(VirtualMachine machine) throws VBoxException {
        throw unsupported("read shared folders");
    }

    /**
     * Mounts an ISO image in the guest's first optical drive, or ejects its
     * current medium when {@code isoPath} is blank.
     */
    default void mountOpticalMedium(VirtualMachine machine, String isoPath) throws VBoxException {
        for (StorageController controller : storageLayout(machine)) {
            for (StorageAttachment attachment : controller.attachments()) {
                if ("dvd".equals(attachment.deviceType())) {
                    attachOpticalMedium(machine, controller.name(), attachment.port(), attachment.device(), isoPath);
                    return;
                }
            }
        }
        throw new VBoxException("The guest has no optical drive. Add one on the Storage settings page first.");
    }

    /**
     * Mounts an ISO image in a specific optical slot, creating the drive when the
     * slot is still empty. A blank {@code isoPath} leaves an empty drive behind.
     */
    default void attachOpticalMedium(VirtualMachine machine, String controllerName, int port, int device,
                                     String isoPath) throws VBoxException {
        throw unsupported("attach optical media");
    }

    default String storageInformation(VirtualMachine machine) throws VBoxException {
        throw unsupported("read storage topology");
    }

    /**
     * Reads the guest's storage controllers together with their attached media.
     */
    default List<StorageController> storageLayout(VirtualMachine machine) throws VBoxException {
        throw unsupported("read the storage layout");
    }

    /**
     * Applies editable controller attributes (name, hardware variant, port count,
     * host I/O cache) to the controller currently registered as {@code currentName}.
     */
    default void updateStorageController(VirtualMachine machine, String currentName, StorageController updated)
            throws VBoxException {
        throw unsupported("update storage controllers");
    }

    default void addStorageController(VirtualMachine machine, StorageControllerSpec controller) throws VBoxException {
        throw unsupported("add storage controllers");
    }

    default void removeStorageController(VirtualMachine machine, String controllerName) throws VBoxException {
        throw unsupported("remove storage controllers");
    }

    default void attachHardDisk(VirtualMachine machine, String controllerName, int port, int device,
                                String mediumPath) throws VBoxException {
        throw unsupported("attach hard disks");
    }

    default String createAndAttachHardDisk(VirtualMachine machine, String controllerName, int port, int device,
                                           String diskPath, int sizeMb, String format, boolean fixed) throws VBoxException {
        throw unsupported("create and attach hard disks");
    }

    default void detachStorageMedium(VirtualMachine machine, String controllerName, int port, int device)
            throws VBoxException {
        throw unsupported("detach storage media");
    }

    default SerialPortSettings serialPortSettings(VirtualMachine machine, int portIndex) throws VBoxException {
        throw unsupported("read serial port settings");
    }

    default void updateSerialPortSettings(VirtualMachine machine, int portIndex, SerialPortSettings settings)
            throws VBoxException {
        throw unsupported("update serial port settings");
    }

    default ParallelPortSettings parallelPortSettings(VirtualMachine machine) throws VBoxException {
        throw unsupported("read parallel port settings");
    }

    default void updateParallelPortSettings(VirtualMachine machine, ParallelPortSettings settings)
            throws VBoxException {
        throw unsupported("update parallel port settings");
    }

    default UsbControllerSettings usbControllerSettings(VirtualMachine machine) throws VBoxException {
        throw unsupported("read USB controller settings");
    }

    default void updateUsbControllerSettings(VirtualMachine machine, UsbControllerSettings settings)
            throws VBoxException {
        throw unsupported("update USB controller settings");
    }

    default List<UsbDeviceFilter> usbDeviceFilters(VirtualMachine machine) throws VBoxException {
        throw unsupported("read USB device filters");
    }

    default void addUsbDeviceFilter(VirtualMachine machine, UsbDeviceFilter filter) throws VBoxException {
        throw unsupported("add USB device filters");
    }

    default void removeUsbDeviceFilter(VirtualMachine machine, String filterName) throws VBoxException {
        throw unsupported("remove USB device filters");
    }

    default GuestIntegrationSettings guestIntegrationSettings(VirtualMachine machine) throws VBoxException {
        throw unsupported("read guest integration settings");
    }

    default void updateGuestIntegrationSettings(VirtualMachine machine, GuestIntegrationSettings settings)
            throws VBoxException {
        throw unsupported("update guest integration settings");
    }

    /**
     * Sends the Ctrl-Alt-Delete keyboard sequence to a running guest.
     */
    void sendCtrlAltDelete(VirtualMachine machine) throws VBoxException;

    /** Sends raw PS/2 scancodes, which must contain the press and the release code of every key. */
    default void sendScancodes(VirtualMachine machine, int... scancodes) throws VBoxException {
        throw unsupported("send keyboard sequences");
    }

    /** Releases every key the guest still believes is held down. */
    default void releaseKeys(VirtualMachine machine) throws VBoxException {
        throw unsupported("release the guest keys");
    }

    /**
     * Captures the guest's primary display as a PNG image.
     *
     * @return complete PNG file contents
     */
    byte[] screenshotPng(VirtualMachine machine) throws VBoxException;

    /**
     * @return the command that was launched, for the message log
     */
    String showDisplay(VirtualMachine machine) throws VBoxException;

    private static VBoxException unsupported(String action) {
        return new VBoxException("The current connection does not support " + action + ".");
    }

    @Override
    default void close() throws VBoxException {
        // No cleanup is required for a command-line client.
    }
}
