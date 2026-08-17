package com.remoteboxjava;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a temporary {@code .rdp} file for Microsoft's RDP client. {@code mstsc.exe}
 * only accepts a fixed session size on its command line ({@code /w} and {@code /h}),
 * which produces a window that cannot be resized, and offers no switch at all for
 * scaling or clipboard redirection. Those exist only as connection-file settings.
 */
final class RdpConnectionFile {

    private RdpConnectionFile() {
    }

    /**
     * The width, height and depth are only the size the window opens with.
     * {@code dynamic resolution} needs the RDP 8.1 display-control channel, which
     * VirtualBox's VRDE server does not implement, so the session keeps that size and
     * the window scrolls. {@code smart sizing} stays off: it stretches the session to
     * the window, which blurs the guest as soon as the window is resized. There is no
     * scaling setting either — {@code desktopscalefactor} is applied by the RDP server,
     * and VRDE ignores it — so the zoom level comes from the compatibility layer mstsc
     * is launched with. {@code use multimon} defaults to 1 and would override the
     * windowed mode.
     *
     * @param shareClipboard requesting any local resource makes mstsc prompt for
     *                       confirmation, because the generated file is unsigned
     */
    static String contents(String host, int port, int width, int height, int depth, boolean shareClipboard) {
        List<String> lines = new ArrayList<>(List.of(
                "full address:s:" + address(host, port),
                "screen mode id:i:1",
                "use multimon:i:0",
                "dynamic resolution:i:1",
                "smart sizing:i:0",
                "desktopwidth:i:" + width,
                "desktopheight:i:" + height,
                "session bpp:i:" + depth,
                "autoreconnection enabled:i:1",
                "redirectclipboard:i:" + (shareClipboard ? 1 : 0),
                // Everything else mstsc would list in its "trust this connection" prompt.
                "redirectwebauthn:i:0",
                "redirectprinters:i:0",
                "redirectcomports:i:0",
                "redirectsmartcards:i:0",
                "redirectlocation:i:0",
                "devicestoredirect:s:",
                "drivestoredirect:s:",
                "usbdevicestoredirect:s:",
                "camerastoredirect:s:",
                /*
                 * VRDE has no verifiable certificate, so level 0 ("connect and don't warn")
                 * suppresses the identity warning. CredSSP must stay at its default: turning
                 * it off makes mstsc abort with "authentication is not enabled and the remote
                 * computer requires that authentication be enabled" before it ever negotiates
                 * with the VRDE server.
                 */
                "authentication level:i:0",
                "prompt for credentials:i:0"));
        lines.add("");
        return String.join("\r\n", lines);
    }

    static Path write(String machineName, String host, int port, int width, int height, int depth,
                      boolean shareClipboard) throws IOException {
        Path file = Files.createTempFile(prefix(machineName), ".rdp");
        file.toFile().deleteOnExit();
        Files.writeString(file, contents(host, port, width, height, depth, shareClipboard),
                StandardCharsets.UTF_8);
        return file;
    }

    /**
     * @return the scale of the primary screen in percent, or 0 when it cannot be
     *         determined
     */
    static int primaryScreenScalePercent() {
        if (GraphicsEnvironment.isHeadless()) {
            return 0;
        }
        try {
            double scale = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration()
                    .getDefaultTransform()
                    .getScaleX();
            return scale > 0 ? (int) Math.round(scale * 100) : 0;
        } catch (RuntimeException | Error ignored) {
            return 0;
        }
    }

    /** Anything outside this set would let the value spill into another .rdp setting. */
    private static String address(String host, int port) {
        String value = host == null ? "" : host.trim();
        if (!value.matches("[A-Za-z0-9._:\\[\\]-]+")) {
            throw new IllegalArgumentException("'" + value + "' is not a valid remote display host.");
        }
        if (value.indexOf(':') >= 0 && !value.startsWith("[")) {
            value = "[" + value + "]";
        }
        return value + ":" + port;
    }

    private static String prefix(String machineName) {
        String cleaned = (machineName == null ? "" : machineName).replaceAll("[^A-Za-z0-9._-]", "-");
        return "remotebox-" + (cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned) + "-";
    }
}
