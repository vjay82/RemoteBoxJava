package com.remoteboxjava;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    private static final Logger LOG = LogManager.getLogger(MstscZoom.class);
    private static final String SESSION_CLASS = "TscShellContainerClass";

    private static final int WM_SYSCOMMAND = 0x0112;
    private static final int MF_BYPOSITION = 0x0400;
    /** The menu command travels in the message's first parameter, the second is unused. */
    private static final long NO_PARAMETER = 0;

    private static final long POLL_INTERVAL_MILLIS = 250;
    /** Long enough for the longest menu label; the percentages are far shorter. */
    private static final int TEXT_BUFFER_LENGTH = 256;

    private static volatile Consumer<String> logger = message -> {
    };

    private final Win32.WindowVisitor topLevelScan = this::examineWindow;
    private final char[] text = new char[TEXT_BUFFER_LENGTH];
    private final String host;
    private final int percent;

    private long session;

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
                LOG.warn("Could not zoom the Remote Desktop session for {}.", host, exception);
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
            session = Win32.NO_HANDLE;
            Win32.enumWindows(topLevelScan);
            if (session != Win32.NO_HANDLE) {
                int applied = zoom(session);
                if (applied > 0) {
                    return applied;
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.debug("Waiting for the Remote Desktop session window was interrupted.");
                return 0;
            }
        }
        return 0;
    }

    /** @return whether to keep enumerating */
    private boolean examineWindow(long window) {
        if (!Win32.isWindowVisible(window)
                || !equalsText(Win32.getClassName(window, text), SESSION_CLASS)
                || !containsText(Win32.getWindowText(window, text), host)) {
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
    private int zoom(long window) {
        long menu = Win32.getSystemMenu(window, false);
        if (menu == Win32.NO_HANDLE) {
            return 0;
        }
        int bestPercent = 0;
        int bestCommand = 0;
        for (int index = 0; index < Win32.getMenuItemCount(menu); index++) {
            long submenu = Win32.getSubMenu(menu, index);
            if (submenu == Win32.NO_HANDLE) {
                continue;
            }
            for (int step = 0; step < Win32.getMenuItemCount(submenu); step++) {
                int offered = percentage(Win32.getMenuString(submenu, step, text, MF_BYPOSITION));
                if (offered > 0 && (bestPercent == 0
                        || Math.abs(offered - percent) < Math.abs(bestPercent - percent))) {
                    bestPercent = offered;
                    bestCommand = Win32.getMenuItemId(submenu, step);
                }
            }
        }
        if (bestPercent == 0 || bestCommand <= 0) {
            return 0;
        }
        Win32.postMessage(window, WM_SYSCOMMAND, bestCommand, NO_PARAMETER);
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
}
