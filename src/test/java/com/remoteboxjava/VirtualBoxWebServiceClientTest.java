package com.remoteboxjava;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualBoxWebServiceClientTest {
    /**
     * Operations that can run for minutes. They hold the client lock only while
     * they start the operation and while they release the session, and wait for
     * the progress object without it so the guest list keeps refreshing meanwhile.
     */
    private static final Set<String> LOCK_INTERNALLY = Set.of(
            "start", "powerOff", "saveState", "takeSnapshot", "restoreSnapshot", "deleteSnapshot");

    /**
     * Operations on the launched display client processes. They touch no SOAP
     * reference, and waiting for a client to exit must not block the guest list.
     */
    private static final Set<String> NO_MANAGED_REFERENCES = Set.of("isDisplayOpen", "closeDisplay");

    @Test
    void publicOperationsSerializeManagedReferenceLifecycles() {
        Arrays.stream(VirtualBoxWebServiceClient.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> !LOCK_INTERNALLY.contains(method.getName()))
                .filter(method -> !NO_MANAGED_REFERENCES.contains(method.getName()))
                // Installing the listener writes a volatile field, not a SOAP reference.
                .filter(method -> !method.getName().equals("setProgressListener"))
                .forEach(VirtualBoxWebServiceClientTest::assertSynchronized);
    }

    @Test
    void exemptedOperationsStillExist() {
        Set<String> declared = Arrays.stream(VirtualBoxWebServiceClient.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        LOCK_INTERNALLY.forEach(name -> assertTrue(declared.contains(name),
                () -> name + " no longer exists, so its exemption is stale"));
        NO_MANAGED_REFERENCES.forEach(name -> assertTrue(declared.contains(name),
                () -> name + " no longer exists, so its exemption is stale"));
    }

    private static void assertSynchronized(Method method) {
        assertTrue(Modifier.isSynchronized(method.getModifiers()),
                () -> method.getName() + " must synchronize access to managed SOAP references");
    }
}