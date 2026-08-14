package com.remoteboxjava;

import javax.swing.SwingUtilities;
import java.awt.Frame;

/**
 * Entry point for embedding RemoteBox into a host application.
 *
 * <p>Unlike {@link RemoteBoxApplication#main(String[])} this API never
 * terminates the JVM, never installs a look and feel and never touches the
 * taskbar icon, so the host keeps full control over its own process and UI.
 * Closing the window only disposes it; calling {@link #showWindow()} again
 * opens a fresh one.</p>
 */
public final class RemoteBox {

    /** The window opened by {@link #showWindow()}. Only touched on the event thread. */
    private static RemoteBoxApplication window;

    private RemoteBox() {
    }

    /**
     * Opens the RemoteBox window, or brings an already open one to the front.
     * May be called from any thread and returns immediately.
     */
    public static void showWindow() {
        showWindow(false);
    }

    static void showWindow(boolean standalone) {
        if (!SwingUtilities.isEventDispatchThread()) {
            // The first read may probe the file system for a RemoteBox installation.
            ApplicationSettings.shared();
        }
        SwingUtilities.invokeLater(() -> open(standalone));
    }

    private static void open(boolean standalone) {
        if (window != null && window.isDisplayable()) {
            window.setExtendedState(window.getExtendedState() & ~Frame.ICONIFIED);
            window.setVisible(true);
            window.toFront();
            window.requestFocus();
            return;
        }
        if (standalone) {
            RemoteBoxApplication.installLookAndFeel();
        }
        window = new RemoteBoxApplication();
        window.setVisible(true);
    }

    /** Called when a window is closed so the next call opens a new one. */
    static void forget(RemoteBoxApplication closed) {
        if (window == closed) {
            window = null;
        }
    }
}
