package com.remoteboxjava;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HMENU;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Zooms a running mstsc session through the "Zoom" entry of its window menu.
 * The zoom is the only scaling mstsc applies on the client side: the connection
 * file's {@code desktopscalefactor} is applied by the RDP server, which VirtualBox's
 * VRDE implementation ignores. The entry has no command-line or .rdp equivalent, so
 * it is chosen from the menu itself once the session window exists.
 */
final class MstscZoom {

    private static final String SESSION_CLASS = "TscShellContainerClass";

    private static final int WM_SYSCOMMAND = 0x0112;
    private static final int MF_BYPOSITION = 0x0400;
    private static final LPARAM NO_LPARAM = new LPARAM(0);

    private static final long POLL_INTERVAL_MILLIS = 250;
    /** Long enough for the longest menu label; the percentages are far shorter. */
    private static final int TEXT_BUFFER_LENGTH = 256;

    private static volatile Consumer<String> logger = message -> {
    };

    private final User32Ex user32 = User32Ex.INSTANCE;
    private final WNDENUMPROC topLevelScan = this::examineWindow;
    private final char[] text = new char[TEXT_BUFFER_LENGTH];
    private final String host;
    private final int percent;

    private HWND session;

    private MstscZoom(String host, int percent) {
        this.host = host;
        this.percent = percent;
    }

    /** The watcher reports long after the launching call has returned. */
    static void setLogger(Consumer<String> messageLog) {
        logger = messageLog;
    }

    /**
     * Waits for the session window on a daemon thread, because the menu only carries
     * the zoom entry once mstsc has connected.
     *
     * @param percent the zoom to select; the nearest offered step is used
     * @param seconds how long the session may take to appear
     */
    static void applyInBackground(String host, int percent, int seconds) {
        if (percent <= 100 || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        Thread watcher = new Thread(() -> {
            try {
                int applied = new MstscZoom(host, percent).apply(seconds);
                if (applied > 0) {
                    logger.accept("Zoomed the Remote Desktop session for " + host + " to " + applied + "%.");
                } else {
                    logger.accept("Could not zoom the Remote Desktop session for " + host + ".");
                }
            } catch (RuntimeException | UnsatisfiedLinkError | NoClassDefFoundError exception) {
                logger.accept("Could not zoom the Remote Desktop session: " + exception);
            }
        }, "mstsc-zoom");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** @return the zoom that was selected, or 0 when none was */
    private int apply(int seconds) {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            session = null;
            user32.EnumWindows(topLevelScan, Pointer.NULL);
            if (session != null) {
                int applied = zoom(session);
                if (applied > 0) {
                    return applied;
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        return 0;
    }

    /** @return whether to keep enumerating */
    private boolean examineWindow(HWND window, Pointer data) {
        if (!user32.IsWindowVisible(window)
                || !equalsText(user32.GetClassName(window, text, text.length), SESSION_CLASS)
                || !containsText(user32.GetWindowText(window, text, text.length), host)) {
            return true;
        }
        session = window;
        return false;
    }

    /**
     * The menu labels are localised, but the zoom steps are written as percentages in
     * every language, so the entry is recognised by its value rather than its text.
     *
     * @return the zoom that was selected, or 0 when the menu offers none
     */
    private int zoom(HWND window) {
        HMENU menu = user32.GetSystemMenu(window, false);
        if (menu == null) {
            return 0;
        }
        int bestPercent = 0;
        int bestCommand = 0;
        for (int index = 0; index < user32.GetMenuItemCount(menu); index++) {
            HMENU submenu = user32.GetSubMenu(menu, index);
            if (submenu == null) {
                continue;
            }
            for (int step = 0; step < user32.GetMenuItemCount(submenu); step++) {
                int offered = percentage(user32.GetMenuString(submenu, step, text, text.length, MF_BYPOSITION));
                if (offered > 0 && (bestPercent == 0
                        || Math.abs(offered - percent) < Math.abs(bestPercent - percent))) {
                    bestPercent = offered;
                    bestCommand = user32.GetMenuItemID(submenu, step);
                }
            }
        }
        if (bestPercent == 0 || bestCommand <= 0) {
            return 0;
        }
        user32.PostMessage(window, WM_SYSCOMMAND, new WPARAM(bestCommand), NO_LPARAM);
        return bestPercent;
    }

    /** @return the percentage in the scratch buffer, or 0 when it holds another label */
    private int percentage(int length) {
        int value = 0;
        int digits = 0;
        for (int index = 0; index < length; index++) {
            char character = text[index];
            if (character >= '0' && character <= '9') {
                value = value * 10 + (character - '0');
                digits++;
            } else if (character == '%') {
                return digits > 0 ? value : 0;
            } else if (digits > 0 && character != ' ' && character != '\u00a0') {
                return 0;
            }
        }
        return 0;
    }

    /** Compares the scratch buffer without materialising a string per window. */
    private boolean equalsText(int length, String expected) {
        return length == expected.length() && regionMatches(0, expected);
    }

    private boolean containsText(int length, String expected) {
        for (int start = 0; start + expected.length() <= length; start++) {
            if (regionMatches(start, expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean regionMatches(int start, String expected) {
        for (int index = 0; index < expected.length(); index++) {
            if (Character.toLowerCase(text[start + index]) != Character.toLowerCase(expected.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** Declared here rather than reused from JNA's User32 so every call is explicit. */
    private interface User32Ex extends StdCallLibrary {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean EnumWindows(WNDENUMPROC callback, Pointer data);

        boolean IsWindowVisible(HWND window);

        int GetClassName(HWND window, char[] buffer, int maximumCount);

        int GetWindowText(HWND window, char[] buffer, int maximumCount);

        HMENU GetSystemMenu(HWND window, boolean revert);

        int GetMenuItemCount(HMENU menu);

        HMENU GetSubMenu(HMENU menu, int position);

        int GetMenuItemID(HMENU menu, int position);

        int GetMenuString(HMENU menu, int item, char[] buffer, int maximumCount, int flags);

        boolean PostMessage(HWND window, int message, WPARAM wParam, LPARAM lParam);
    }
}
