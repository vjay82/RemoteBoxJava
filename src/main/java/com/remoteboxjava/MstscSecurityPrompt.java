package com.remoteboxjava;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Confirms the "Remote Desktop Connection security warning" that mstsc raises for
 * an unsigned .rdp file that asks for a local resource. There is no .rdp setting
 * or command-line switch that suppresses it, and Windows offers no "don't ask
 * again" option for an unknown publisher, so the prompt is answered through the
 * dialog's own controls instead.
 *
 * <p>One instance polls for one prompt, so its scratch buffers and enumeration
 * callbacks are allocated once instead of on every scan.
 */
final class MstscSecurityPrompt {

    private static final Logger LOG = LogManager.getLogger(MstscSecurityPrompt.class);
    private static final String DIALOG_CLASS = "#32770";
    /** Control identifiers are stable across Windows display languages. */
    private static final int CLIPBOARD_CHECKBOX = 16333;
    private static final int REMOTE_COMPUTER_LABEL = 16307;
    private static final int CONNECT_BUTTON = 1;

    private static final int BM_GETCHECK = 0x00F0;
    private static final int BM_CLICK = 0x00F5;
    private static final int BST_CHECKED = 1;
    /** Neither message this class sends carries a parameter. */
    private static final long NO_PARAMETER = 0;

    private static final long POLL_INTERVAL_MILLIS = 200;
    /** Long enough for a class name; the label only ever holds a host name. */
    private static final int TEXT_BUFFER_LENGTH = 256;

    private static volatile Consumer<String> logger = message -> {
    };

    private final Win32.WindowVisitor topLevelScan = this::examineWindow;
    private final Win32.WindowVisitor controlScan = this::examineControl;
    private final char[] text = new char[TEXT_BUFFER_LENGTH];
    private final String host;

    private long prompt;
    private long clipboard;
    private long connect;
    private long remoteComputer;

    private MstscSecurityPrompt(String host) {
        this.host = host;
    }

    /** The watcher reports long after the launching call has returned. */
    static void setLogger(Consumer<String> messageLog) {
        logger = messageLog;
    }

    /**
     * Watches for the prompt on a daemon thread so the caller is not blocked for
     * the whole timeout when no prompt appears.
     *
     * @param host    the remote computer the prompt must name, so a prompt for
     *                some other connection is never answered
     * @param seconds how long the prompt may take to appear
     */
    static void confirmInBackground(String host, int seconds) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        Thread watcher = new Thread(() -> {
            try {
                if (new MstscSecurityPrompt(host).confirm(seconds)) {
                    logger.accept("Confirmed the Remote Desktop security prompt for " + host + ".");
                } else {
                    LOG.debug("No Remote Desktop security prompt for {} appeared within {} s.", host, seconds);
                }
            } catch (RuntimeException | UnsatisfiedLinkError | NoClassDefFoundError exception) {
                LOG.warn("Could not confirm the Remote Desktop security prompt for {}.", host, exception);
                logger.accept("Could not confirm the Remote Desktop security prompt: " + exception);
            }
        }, "mstsc-security-prompt");
        watcher.setDaemon(true);
        watcher.start();
    }

    private boolean confirm(int seconds) {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            prompt = Win32.NO_HANDLE;
            Win32.enumWindows(topLevelScan);
            if (prompt != Win32.NO_HANDLE) {
                answer();
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOG.debug("Waiting for the Remote Desktop security prompt was interrupted.");
                return false;
            }
        }
        return false;
    }

    /** @return whether to keep enumerating */
    private boolean examineWindow(long window) {
        if (!Win32.isWindowVisible(window) || !matches(Win32.getClassName(window, text), DIALOG_CLASS)) {
            return true;
        }
        findControls(window);
        if (clipboard == Win32.NO_HANDLE || connect == Win32.NO_HANDLE || remoteComputer == Win32.NO_HANDLE
                || !matches(Win32.getWindowText(remoteComputer, text), host)) {
            return true;
        }
        prompt = window;
        return false;
    }

    /**
     * Collects all three controls in one pass. The subtree has to be walked
     * because the checkbox is nested in a container, where {@code GetDlgItem}
     * would not find it.
     */
    private void findControls(long dialog) {
        clipboard = Win32.NO_HANDLE;
        connect = Win32.NO_HANDLE;
        remoteComputer = Win32.NO_HANDLE;
        Win32.enumChildWindows(dialog, controlScan);
    }

    /** @return whether to keep enumerating */
    private boolean examineControl(long control) {
        switch (Win32.getDialogControlId(control)) {
            case CLIPBOARD_CHECKBOX -> clipboard = control;
            case CONNECT_BUTTON -> connect = control;
            case REMOTE_COMPUTER_LABEL -> remoteComputer = control;
            default -> {
                return true;
            }
        }
        return clipboard == Win32.NO_HANDLE || connect == Win32.NO_HANDLE || remoteComputer == Win32.NO_HANDLE;
    }

    private void answer() {
        if (checkState(clipboard) != BST_CHECKED) {
            Win32.sendMessage(clipboard, BM_CLICK, NO_PARAMETER, NO_PARAMETER);
        }
        // Posted, because a sent click would block this thread until mstsc connects.
        Win32.postMessage(connect, BM_CLICK, NO_PARAMETER, NO_PARAMETER);
    }

    private int checkState(long checkbox) {
        return (int) Win32.sendMessage(checkbox, BM_GETCHECK, NO_PARAMETER, NO_PARAMETER);
    }

    /** Compares the scratch buffer without materialising a string per window. */
    private boolean matches(int length, String expected) {
        if (length != expected.length()) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            if (Character.toLowerCase(text[index]) != Character.toLowerCase(expected.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
