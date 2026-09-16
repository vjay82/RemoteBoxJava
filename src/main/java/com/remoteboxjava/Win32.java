package com.remoteboxjava;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;

/**
 * The Win32 calls RemoteBox needs, bound through the Foreign Function &amp; Memory
 * API. Windows handles are passed as plain addresses, so nothing but this class
 * has to deal with native memory.
 *
 * <p>Every binding degrades to a stub that throws on use rather than failing class
 * initialisation, because this class is also loaded where its libraries do not
 * exist: the callers decide per feature whether the platform supports it.</p>
 */
final class Win32 {

    private static final Logger LOG = LogManager.getLogger(Win32.class);

    /** The value of a handle that Windows did not return: no window, no menu. */
    static final long NO_HANDLE = 0;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32 = library("user32");
    private static final SymbolLookup CRYPT32 = library("crypt32");
    private static final SymbolLookup KERNEL32 = library("kernel32");

    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfChar WCHAR = ValueLayout.JAVA_CHAR;
    private static final AddressLayout POINTER = ValueLayout.ADDRESS;

    /** {@code BOOL CALLBACK EnumWindowsProc(HWND, LPARAM)} */
    private static final FunctionDescriptor ENUM_WINDOWS_PROC = FunctionDescriptor.of(INT, POINTER, LONG);

    private static final MethodHandle EnumWindows = downcall(USER32, "EnumWindows", FunctionDescriptor.of(INT, POINTER, LONG));
    private static final MethodHandle EnumChildWindows = downcall(USER32, "EnumChildWindows", FunctionDescriptor.of(INT, POINTER, POINTER, LONG));
    private static final MethodHandle IsWindowVisible = downcall(USER32, "IsWindowVisible", FunctionDescriptor.of(INT, POINTER));
    private static final MethodHandle GetClassNameW = downcall(USER32, "GetClassNameW", FunctionDescriptor.of(INT, POINTER, POINTER, INT));
    private static final MethodHandle GetWindowTextW = downcall(USER32, "GetWindowTextW", FunctionDescriptor.of(INT, POINTER, POINTER, INT));
    private static final MethodHandle GetDlgCtrlID = downcall(USER32, "GetDlgCtrlID", FunctionDescriptor.of(INT, POINTER));
    private static final MethodHandle SendMessageW = downcall(USER32, "SendMessageW", FunctionDescriptor.of(LONG, POINTER, INT, LONG, LONG));
    private static final MethodHandle PostMessageW = downcall(USER32, "PostMessageW", FunctionDescriptor.of(INT, POINTER, INT, LONG, LONG));
    private static final MethodHandle GetSystemMenu = downcall(USER32, "GetSystemMenu", FunctionDescriptor.of(POINTER, POINTER, INT));
    private static final MethodHandle GetMenuItemCount = downcall(USER32, "GetMenuItemCount", FunctionDescriptor.of(INT, POINTER));
    private static final MethodHandle GetSubMenu = downcall(USER32, "GetSubMenu", FunctionDescriptor.of(POINTER, POINTER, INT));
    private static final MethodHandle GetMenuItemID = downcall(USER32, "GetMenuItemID", FunctionDescriptor.of(INT, POINTER, INT));
    private static final MethodHandle GetMenuStringW = downcall(USER32, "GetMenuStringW", FunctionDescriptor.of(INT, POINTER, INT, POINTER, INT, INT));

    private static final MethodHandle CryptProtectData = downcall(CRYPT32, "CryptProtectData",
            FunctionDescriptor.of(INT, POINTER, POINTER, POINTER, POINTER, POINTER, INT, POINTER));
    private static final MethodHandle CryptUnprotectData = downcall(CRYPT32, "CryptUnprotectData",
            FunctionDescriptor.of(INT, POINTER, POINTER, POINTER, POINTER, POINTER, INT, POINTER));
    /** Returns HLOCAL, bound as void because the result is never read. */
    private static final MethodHandle LocalFree = downcall(KERNEL32, "LocalFree", FunctionDescriptor.ofVoid(POINTER));

    /** No prompt may appear: the secret is written from a background thread. */
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;
    /** {@code DATA_BLOB { DWORD cbData; BYTE *pbData; }}, with the pointer 8-byte aligned. */
    private static final long DATA_BLOB_SIZE = 16;
    private static final long DATA_BLOB_DATA_OFFSET = 8;

    private Win32() {
    }

    /** Receives one window of an enumeration; returns whether to keep enumerating. */
    @FunctionalInterface
    interface WindowVisitor {
        boolean visit(long window);
    }

    static void enumWindows(WindowVisitor visitor) {
        try (Arena arena = Arena.ofConfined()) {
            // The result only says whether the enumeration ran to its end, which a
            // visitor that found what it looked for deliberately prevents.
            int ignored = (int) EnumWindows.invokeExact(upcall(arena, visitor), 0L);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static void enumChildWindows(long parent, WindowVisitor visitor) {
        try (Arena arena = Arena.ofConfined()) {
            int ignored = (int) EnumChildWindows.invokeExact(handle(parent), upcall(arena, visitor), 0L);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static boolean isWindowVisible(long window) {
        try {
            return (int) IsWindowVisible.invokeExact(handle(window)) != 0;
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** @return how many characters of the window's class name were written into the buffer */
    static int getClassName(long window, char[] buffer) {
        return readText(GetClassNameW, window, buffer);
    }

    /** @return how many characters of the window's text were written into the buffer */
    static int getWindowText(long window, char[] buffer) {
        return readText(GetWindowTextW, window, buffer);
    }

    static int getDialogControlId(long control) {
        try {
            return (int) GetDlgCtrlID.invokeExact(handle(control));
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static long sendMessage(long window, int message, long wParam, long lParam) {
        try {
            return (long) SendMessageW.invokeExact(handle(window), message, wParam, lParam);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static void postMessage(long window, int message, long wParam, long lParam) {
        try {
            int posted = (int) PostMessageW.invokeExact(handle(window), message, wParam, lParam);
            if (posted == 0) {
                LOG.debug("Message {} could not be posted to window {}.", message, window);
            }
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** @return the window menu, or {@link #NO_HANDLE} when the window has none */
    static long getSystemMenu(long window, boolean revert) {
        try {
            return address((MemorySegment) GetSystemMenu.invokeExact(handle(window), revert ? 1 : 0));
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static int getMenuItemCount(long menu) {
        try {
            return (int) GetMenuItemCount.invokeExact(handle(menu));
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** @return the submenu at that position, or {@link #NO_HANDLE} when the item has none */
    static long getSubMenu(long menu, int position) {
        try {
            return address((MemorySegment) GetSubMenu.invokeExact(handle(menu), position));
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static int getMenuItemId(long menu, int position) {
        try {
            return (int) GetMenuItemID.invokeExact(handle(menu), position);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** @return how many characters of the item's label were written into the buffer */
    static int getMenuString(long menu, int item, char[] buffer, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocate(WCHAR, buffer.length);
            int length = (int) GetMenuStringW.invokeExact(handle(menu), item, text, buffer.length, flags);
            return copyText(text, length, buffer);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /**
     * Encrypts data so that only the current user account on this machine can read
     * it back again.
     *
     * @throws IllegalStateException when Windows refused to protect the data
     */
    static byte[] protectData(byte[] plain) {
        return crypt(CryptProtectData, "CryptProtectData", plain);
    }

    /**
     * Decrypts a blob produced by {@link #protectData}.
     *
     * @throws IllegalStateException when the blob belongs to another user or machine,
     *                               or is not a DPAPI blob at all
     */
    static byte[] unprotectData(byte[] blob) {
        return crypt(CryptUnprotectData, "CryptUnprotectData", blob);
    }

    private static byte[] crypt(MethodHandle function, String name, byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = dataBlob(arena, data);
            MemorySegment out = arena.allocate(DATA_BLOB_SIZE, 8);
            out.fill((byte) 0);
            int succeeded = (int) function.invokeExact(in, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, CRYPTPROTECT_UI_FORBIDDEN, out);
            if (succeeded == 0) {
                throw new IllegalStateException(name + " failed.");
            }
            int size = out.get(INT, 0);
            MemorySegment result = out.get(POINTER, DATA_BLOB_DATA_OFFSET);
            if (size <= 0 || result.address() == 0) {
                throw new IllegalStateException(name + " returned no data.");
            }
            byte[] bytes = result.reinterpret(size).toArray(BYTE);
            LocalFree.invokeExact(result);
            return bytes;
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    private static MemorySegment dataBlob(Arena arena, byte[] data) {
        MemorySegment bytes = arena.allocate(BYTE, data.length);
        MemorySegment.copy(data, 0, bytes, BYTE, 0, data.length);
        MemorySegment blob = arena.allocate(DATA_BLOB_SIZE, 8);
        blob.fill((byte) 0);
        blob.set(INT, 0, data.length);
        blob.set(POINTER, DATA_BLOB_DATA_OFFSET, bytes);
        return blob;
    }

    private static int readText(MethodHandle function, long window, char[] buffer) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocate(WCHAR, buffer.length);
            int length = (int) function.invokeExact(handle(window), text, buffer.length);
            return copyText(text, length, buffer);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    private static int copyText(MemorySegment text, int length, char[] buffer) {
        int copied = Math.max(0, Math.min(length, buffer.length));
        MemorySegment.copy(text, WCHAR, 0, buffer, 0, copied);
        return copied;
    }

    /**
     * Builds the native callback for one enumeration. It lives in the arena of that
     * call, which is closed once the enumeration has returned.
     */
    private static MemorySegment upcall(Arena arena, WindowVisitor visitor) {
        try {
            MethodHandle adapter = MethodHandles.lookup().findStatic(Win32.class, "visitWindow",
                    MethodType.methodType(int.class, WindowVisitor.class, MemorySegment.class, long.class));
            return LINKER.upcallStub(adapter.bindTo(visitor), ENUM_WINDOWS_PROC, arena);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException("The window enumeration callback could not be bound.", failure);
        }
    }

    /** Reached as a method handle, never called directly. */
    private static int visitWindow(WindowVisitor visitor, MemorySegment window, long data) {
        // A Throwable escaping into the native enumeration loop would take the whole
        // process down without a stack trace, so the scan ends quietly instead.
        try {
            return visitor.visit(address(window)) ? 1 : 0;
        } catch (Throwable failure) {
            LOG.warn("A window could not be examined, so the enumeration was stopped.", failure);
            return 0;
        }
    }

    private static MemorySegment handle(long value) {
        return MemorySegment.ofAddress(value);
    }

    private static long address(MemorySegment segment) {
        return segment == null ? NO_HANDLE : segment.address();
    }

    /** @return null off Windows and whenever the library cannot be loaded */
    private static SymbolLookup library(String name) {
        try {
            return SymbolLookup.libraryLookup(name, Arena.global());
        } catch (IllegalArgumentException | UnsatisfiedLinkError failure) {
            LOG.debug("The system library {} is not available: {}", name, failure.toString());
            return null;
        }
    }

    /**
     * Binds a native function, or a stub that reports the missing function when it is
     * called. Field initialisation therefore succeeds on every operating system, which
     * it has to: the callers of this class are loaded everywhere.
     */
    private static MethodHandle downcall(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        Optional<MemorySegment> symbol = library == null ? Optional.empty() : library.find(name);
        if (symbol.isEmpty()) {
            return unavailable(name, descriptor.toMethodType());
        }
        return LINKER.downcallHandle(symbol.get(), descriptor);
    }

    private static MethodHandle unavailable(String name, MethodType type) {
        MethodHandle thrower = MethodHandles.throwException(type.returnType(), UnsatisfiedLinkError.class);
        MethodHandle error = MethodHandles.insertArguments(Unavailable.ERROR, 0, name);
        return MethodHandles.dropArguments(MethodHandles.foldArguments(thrower, error), 0, type.parameterList());
    }

    /** Reached as a method handle, never called directly. */
    private static UnsatisfiedLinkError unavailableError(String name) {
        return new UnsatisfiedLinkError("The Windows function " + name + " is not available on this system.");
    }

    /** Holder, so the lookup does not depend on where it sits in this class's field order. */
    private static final class Unavailable {

        static final MethodHandle ERROR;

        static {
            try {
                ERROR = MethodHandles.lookup().findStatic(Win32.class, "unavailableError",
                        MethodType.methodType(UnsatisfiedLinkError.class, String.class));
            } catch (NoSuchMethodException | IllegalAccessException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure);
    }
}
