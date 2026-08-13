package com.remoteboxjava;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualBoxWebServiceClientTest {
    @Test
    void publicOperationsSerializeManagedReferenceLifecycles() {
        Arrays.stream(VirtualBoxWebServiceClient.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .forEach(VirtualBoxWebServiceClientTest::assertSynchronized);
    }

    private static void assertSynchronized(Method method) {
        assertTrue(Modifier.isSynchronized(method.getModifiers()),
                () -> method.getName() + " must synchronize access to managed SOAP references");
    }
}