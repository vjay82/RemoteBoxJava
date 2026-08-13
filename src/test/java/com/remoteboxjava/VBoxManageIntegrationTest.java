package com.remoteboxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in smoke test for a real local or SSH VBoxManage connection.
 *
 * <p>Enable it with {@code VBOX_INTEGRATION_TEST=true}. Optionally set
 * {@code VBOX_INTEGRATION_COMMAND} to a complete command prefix such as
 * {@code ssh user@example.test VBoxManage}. The test intentionally performs
 * no mutating operations, so it is safe to run against an existing host.</p>
 */
class VBoxManageIntegrationTest {
    @Test
    void connectsAndListsGuestsWithoutMutation() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("VBOX_INTEGRATION_TEST")),
                "Set VBOX_INTEGRATION_TEST=true to run against a real VBoxManage host.");

        String command = System.getenv("VBOX_INTEGRATION_COMMAND");
        VBoxManageClient client = new VBoxManageClient(command);
        String version = client.version();

        assertFalse(version.isBlank(), "VBoxManage must report a version.");
        client.listMachines();
    }
}
