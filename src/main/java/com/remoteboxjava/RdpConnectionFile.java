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

    /** The only values mstsc accepts for {@code desktopscalefactor}. */
    private static final int[] SCALE_PERCENTAGES = {100, 125, 150, 175, 200, 250, 300, 400, 500};

    private RdpConnectionFile() {
    }

    /**
     * The width, height and depth are only the size the window opens with.
     * {@code dynamic resolution} needs the RDP 8.1 display-control channel, which
     * VirtualBox's VRDE server does not implement, so {@code smart sizing} is what
     * actually makes the window resizable: mstsc then scales the session to fit.
     * {@code use multimon} defaults to 1 and would override the windowed mode.
     *
     * @param scalePercent {@code desktopscalefactor}, or 0 to let mstsc decide
     * @param shareClipboard requesting any local resource makes mstsc prompt for
     *                       confirmation, because the generated file is unsigned
     */
    static String contents(String host, int port, int width, int height, int depth, int scalePercent,
                           boolean shareClipboard) {
        List<String> lines = new ArrayList<>(List.of(
                "full address:s:" + address(host, port),
                "screen mode id:i:1",
                "use multimon:i:0",
                "dynamic resolution:i:1",
                "smart sizing:i:1",
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
        if (scalePercent > 0) {
            lines.add("desktopscalefactor:i:" + scalePercent);
        }
        lines.add("");
        return String.join("\r\n", lines);
    }

    static Path write(String machineName, String host, int port, int width, int height, int depth, int scalePercent,
                      boolean shareClipboard) throws IOException {
        Path file = Files.createTempFile(prefix(machineName), ".rdp");
        file.toFile().deleteOnExit();
        Files.writeString(file, contents(host, port, width, height, depth, scalePercent, shareClipboard),
                StandardCharsets.UTF_8);
        return file;
    }

    /**
     * @return the scale matching the primary screen's DPI, or 0 when it cannot be
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
            return nearestScalePercent(scale * 100);
        } catch (RuntimeException | Error ignored) {
            return 0;
        }
    }

    /** mstsc ignores a {@code desktopscalefactor} outside its fixed list. */
    static int nearestScalePercent(double percent) {
        if (percent <= 0) {
            return 0;
        }
        int nearest = SCALE_PERCENTAGES[0];
        for (int candidate : SCALE_PERCENTAGES) {
            if (Math.abs(candidate - percent) < Math.abs(nearest - percent)) {
                nearest = candidate;
            }
        }
        return nearest;
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
