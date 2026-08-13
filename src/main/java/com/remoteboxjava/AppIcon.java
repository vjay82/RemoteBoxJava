package com.remoteboxjava;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The application icon, drawn with Java2D so it stays sharp at every window,
 * taskbar and dock size instead of being resampled from one bitmap.
 *
 * <p>The mark is a remote display: a screen on a blue badge with a broadcast
 * glyph. Below 24 pixels the glyph is reduced to a single arc so the silhouette
 * stays readable.</p>
 */
public final class AppIcon {
    private static final int[] SIZES = {16, 20, 24, 32, 48, 64, 128, 256};

    private static final Color BADGE_TOP = Color.decode("#3B82F6");
    private static final Color BADGE_BOTTOM = Color.decode("#17357F");
    private static final Color SCREEN = Color.decode("#F4F7FB");
    private static final Color GLYPH = Color.decode("#1E5BC6");

    private AppIcon() {
    }

    /** Icon renditions for {@code JFrame.setIconImages}, smallest first. */
    public static List<Image> windowIcons() {
        return java.util.Arrays.stream(SIZES).mapToObj(AppIcon::render).map(Image.class::cast).toList();
    }

    public static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            paintBadge(graphics, size);
            paintScreen(graphics, size);
            paintBroadcast(graphics, size);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void paintBadge(Graphics2D graphics, double size) {
        double inset = size * 0.02;
        graphics.setPaint(new GradientPaint(0f, (float) inset, BADGE_TOP, 0f, (float) (size - inset), BADGE_BOTTOM));
        graphics.fill(new RoundRectangle2D.Double(inset, inset, size - 2 * inset, size - 2 * inset,
                size * 0.24, size * 0.24));
    }

    private static void paintScreen(Graphics2D graphics, double size) {
        graphics.setColor(SCREEN);
        graphics.fill(new RoundRectangle2D.Double(size * 0.185, size * 0.225, size * 0.63, size * 0.435,
                size * 0.09, size * 0.09));
        graphics.fill(new Rectangle2D.Double(size * 0.455, size * 0.655, size * 0.09, size * 0.105));
        graphics.fill(new RoundRectangle2D.Double(size * 0.315, size * 0.755, size * 0.37, size * 0.075,
                size * 0.04, size * 0.04));
    }

    private static void paintBroadcast(Graphics2D graphics, double size) {
        double originX = size * 0.305;
        double originY = size * 0.595;
        graphics.setColor(GLYPH);
        graphics.fill(new Ellipse2D.Double(originX - size * 0.035, originY - size * 0.035,
                size * 0.07, size * 0.07));

        double thickness = Math.max(1.0, size * 0.055);
        graphics.setStroke(new BasicStroke((float) thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // Fine arcs disappear below 24 pixels, so only the widest one is kept there.
        double[] radii = size < 24 ? new double[]{0.20} : new double[]{0.115, 0.185, 0.255};
        for (double radius : radii) {
            double scaled = size * radius;
            graphics.draw(new Arc2D.Double(originX - scaled, originY - scaled, scaled * 2, scaled * 2,
                    5, 80, Arc2D.OPEN));
        }
    }
}
