package com.remoteboxjava;

/**
 * Editable subset of a VirtualBox machine configuration.
 *
 * <p>The fields correspond to the common General, System, Display and Remote
 * Display pages in RemoteBox's Settings dialog. Transport implementations
 * apply only values supported by their VirtualBox endpoint and report an
 * explicit error for an unsupported operation.</p>
 */
public record MachineSettings(
        String name,
        String description,
        String groups,
        String osType,
        int memoryMb,
        int cpuCount,
        int videoMemoryMb,
        boolean vrdeEnabled,
        String vrdePort
) {
    public static MachineSettings from(VirtualMachine machine) {
        return new MachineSettings(
                machine.name(),
                machine.description(),
                machine.groups(),
                machine.osType(),
                machine.memoryMb(),
                machine.cpuCount(),
                16,
                !machine.vrdePort().isBlank(),
                machine.vrdePort()
        );
    }
}
