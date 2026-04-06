package it.flavianopetrocchi.jpdfbookmarks;

import static java.lang.Math.max;
import static java.lang.Math.min;
import java.io.IOException;

import java.awt.Component;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import static java.awt.image.BufferedImage.TYPE_INT_RGB;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Displays clickable thumbnails of pages in a PDF file. The thumbnails are of
 * class ThumbnailButton, a subclass of JButton. They are stacked in a vertical
 * {@link Box}, centered inside a horizontal row that is the JViewport view of this
 * {@link JScrollPane}.
 *
 * @author rmfritz
 */
public class ThumbnailsPane extends JScrollPane implements PageChangedListener {

    /**
     * Legacy base size (px); also the minimum width/height used when the sidebar is narrow.
     */
    static final float THUMBSIZE = 96;

    private static final int THUMB_PIXEL_MIN = 96;
    /** Cap so very wide sidebars do not build huge off-screen bitmaps. */
    private static final int THUMB_PIXEL_MAX = 200;
    /**
     * Total horizontal space left unused around the thumb column when deriving {@link #thumbRenderPixels}
     * (larger ⇒ smaller thumbnails vs panel width).
     */
    private static final int THUMB_VIEWPORT_MARGIN = 80;
    /** Further reduces the side used for the bitmap after {@link #THUMB_VIEWPORT_MARGIN}. */
    private static final int THUMB_AVAIL_EXTRA_TRIM = 24;
    private static final int THUMB_CARD_LINE = 1;
    /**
     * Space between the outer line border and the page image (and label) inside each thumbnail card.
     */
    private static final int THUMB_CARD_INSET_TOP = 6;
    private static final int THUMB_CARD_INSET_LEFT = 6;
    private static final int THUMB_CARD_INSET_RIGHT = 6;
    private static final int THUMB_CARD_INSET_BOTTOM = 4;
    /** Area reserved under the icon for page number + gap. */
    private static final int THUMB_CARD_LABEL_ROW = 22;
    /** Padding around the whole thumbnail column inside the centered strip. */
    private static final int THUMB_COLUMN_PAD_TOP = 8;
    private static final int THUMB_COLUMN_PAD_SIDES = 22;
    private static final int THUMB_COLUMN_PAD_BOTTOM = 8;
    /** Space between thumbnail “cards” (smaller ⇒ more rows fit vertically). */
    private static final int THUMB_VERTICAL_GAP = 5;
    /**
     * PDF is rendered at this factor times the nominal scale, then downsampled with bicubic filtering
     * for sharper thumbnails (similar to high-DPI preview in other viewers).
     */
    private static final float THUMB_RENDER_SUPERSAMPLE = 2f;
    private static final Color THUMB_VIEWPORT_BG = new Color(0xf0f0f0);
    private static final Color THUMB_CARD_BORDER = new Color(0xc8c8c8);

    /** The PDFBox PDF document. */
    private PDDocument document;
    /** A PDFBox renderer used to generate page thumbnails. */
    private PDFRenderer thumbnailRenderer;
    /** The Swing Box which will contain the thumbnailButton instances. */
    private final Box thumbnailBox;
    /** Horizontal row (glue | column | glue) so the thumbnail column stays centered. */
    private Box centeringRow;
    /** An array of thumbnail buttons, one per page. */
    private ThumbnailButton[] thumbnailButtons;
    /** Viewport listener for lazy thumbnail generation (at most one). */
    private ChangeListener thumbnailViewportListener;
    /** While true, viewport-driven generation is skipped (e.g. document closed mid-save). */
    private volatile boolean thumbnailGenSuspended;

    /** Current edge length (px) of the square thumbnail image; follows sidebar width. */
    private int thumbRenderPixels = THUMB_PIXEL_MIN;

    /**
     * This constructor sets up the document, renderer, thumbnail button array,
     * and box that will contain the buttons. To avoid referencing overridable
     * methods in a constructor, the rest of the work of setting up the pane is
     * completed in setupThumbnails().
     *
     * @param doc the PDF document for which thumbnails will be displayed
     */
    public ThumbnailsPane(PDDocument doc) {
        document = doc;
        thumbnailRenderer = new PDFRenderer(doc);
        thumbnailBox = Box.createVerticalBox();
        thumbnailBox.setBorder(BorderFactory.createEmptyBorder(
                THUMB_COLUMN_PAD_TOP, THUMB_COLUMN_PAD_SIDES,
                THUMB_COLUMN_PAD_BOTTOM, THUMB_COLUMN_PAD_SIDES));
        thumbnailButtons = new ThumbnailButton[document.getNumberOfPages()];
    }

    private static int computeThumbPixels(int viewportExtentWidth) {
        int inner = viewportExtentWidth - THUMB_VIEWPORT_MARGIN;
        if (inner <= THUMB_PIXEL_MIN + THUMB_AVAIL_EXTRA_TRIM) {
            return THUMB_PIXEL_MIN;
        }
        int avail = inner - THUMB_AVAIL_EXTRA_TRIM;
        return min(THUMB_PIXEL_MAX, max(THUMB_PIXEL_MIN, avail));
    }

    private void ensureCenteringRowAttached() {
        JViewport vp = getViewport();
        if (thumbnailBox.getParent() == vp) {
            vp.remove(thumbnailBox);
        }
        if (centeringRow == null) {
            centeringRow = Box.createHorizontalBox();
            centeringRow.add(Box.createHorizontalGlue());
            centeringRow.add(thumbnailBox);
            centeringRow.add(Box.createHorizontalGlue());
        } else if (thumbnailBox.getParent() != centeringRow) {
            if (thumbnailBox.getParent() != null) {
                thumbnailBox.getParent().remove(thumbnailBox);
            }
            centeringRow.removeAll();
            centeringRow.add(Box.createHorizontalGlue());
            centeringRow.add(thumbnailBox);
            centeringRow.add(Box.createHorizontalGlue());
        }
        if (centeringRow.getParent() != vp) {
            if (centeringRow.getParent() != null) {
                centeringRow.getParent().remove(centeringRow);
            }
            vp.add(centeringRow);
        }
    }

    private ImageIcon createScaledPlaceholder() {
        ImageIcon raw = new ImageIcon(
                getClass().getResource("/it/flavianopetrocchi/jpdfbookmarks/gfx/nothumb.png"));
        if (raw.getIconWidth() <= 0) {
            return raw;
        }
        Image sm = raw.getImage().getScaledInstance(
                thumbRenderPixels, thumbRenderPixels, Image.SCALE_SMOOTH);
        return new ImageIcon(sm);
    }

    private void applyButtonThumbSize(ThumbnailButton tb) {
        int line = THUMB_CARD_LINE;
        int w = thumbRenderPixels + 2 * line + THUMB_CARD_INSET_LEFT + THUMB_CARD_INSET_RIGHT;
        int h = thumbRenderPixels + 2 * line + THUMB_CARD_INSET_TOP + THUMB_CARD_INSET_BOTTOM + THUMB_CARD_LABEL_ROW;
        Dimension d = new Dimension(w, h);
        tb.setPreferredSize(d);
        tb.setMinimumSize(d);
        tb.setMaximumSize(d);
    }

    private boolean iconApproximatelyMatchesThumb(Icon ic) {
        if (ic == null) {
            return false;
        }
        int iw = ic.getIconWidth();
        int ih = ic.getIconHeight();
        if (iw <= 0 || ih <= 0) {
            return true;
        }
        return Math.abs(iw - thumbRenderPixels) <= 3 && Math.abs(ih - thumbRenderPixels) <= 3;
    }

    /**
     * Updates {@link #thumbRenderPixels} from the viewport width. When {@code refreshThumbsIfSizeChanged}
     * is true, buttons are resized; existing page thumbnails are rescaled in memory (no placeholder flash).
     * Pages still on the placeholder keep the scaled placeholder.
     */
    private void syncThumbRenderSizeToViewport(boolean refreshThumbsIfSizeChanged) {
        int next = computeThumbPixels(getViewport().getExtentSize().width);
        if (next == thumbRenderPixels) {
            return;
        }
        thumbRenderPixels = next;
        getVerticalScrollBar().setUnitIncrement(max(1, thumbRenderPixels / 3));
        if (!refreshThumbsIfSizeChanged || thumbnailButtons == null) {
            return;
        }
        ImageIcon placeholder = createScaledPlaceholder();
        for (ThumbnailButton tb : thumbnailButtons) {
            if (tb == null) {
                continue;
            }
            if (tb.hasRealThumb()) {
                rescaleButtonThumbToCurrentPixelSize(tb);
            } else {
                tb.setThumb(placeholder, false);
            }
            applyButtonThumbSize(tb);
        }
    }

    private BufferedImage iconToBufferedImage(Icon icon) {
        if (!(icon instanceof ImageIcon)) {
            return null;
        }
        return toBufferedImage(((ImageIcon) icon).getImage());
    }

    /**
     * Re-applies portrait / landscape / square layout to an already composed thumb bitmap (avoids PDF re-render).
     */
    private BufferedImage rescaleExistingThumbImage(BufferedImage src, int ts) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= 0 || sh <= 0) {
            return null;
        }
        if (sh >= sw) {
            int nh = ts;
            int nw = max(1, (int) Math.round(sw * (double) ts / sh));
            return scaleImageHighQuality(src, nw, nh);
        }
        int nw = ts;
        int nh = max(1, (int) Math.round(sh * (double) ts / sw));
        BufferedImage scaled = scaleImageHighQuality(src, nw, nh);
        return embedLandscapeInSquare(scaled, ts);
    }

    private void rescaleButtonThumbToCurrentPixelSize(ThumbnailButton tb) {
        BufferedImage src = iconToBufferedImage(tb.getIcon());
        if (src == null) {
            tb.setThumb(createScaledPlaceholder(), false);
            return;
        }
        BufferedImage out = rescaleExistingThumbImage(src, thumbRenderPixels);
        if (out != null) {
            tb.setThumb(new ImageIcon(out), true);
        }
    }

    /**
     * Create the Box for thumbnail buttons and populate it.
     */
    public void setupThumbnails() {
        ensureCenteringRowAttached();
        getViewport().setBackground(THUMB_VIEWPORT_BG);
        syncThumbRenderSizeToViewport(false);
        this.getVerticalScrollBar().setUnitIncrement(max(1, thumbRenderPixels / 3));
        if (thumbnailViewportListener == null) {
            thumbnailViewportListener = new thumbnailGenControl();
            this.getViewport().addChangeListener(thumbnailViewportListener);
        }

        populateThumbnailButtons(null);
    }

    /**
     * Rebinds this pane to a new document after the previous one was closed (e.g. in-place save).
     * Keeps the same {@code JScrollPane} in the UI to avoid a visible strip refresh.
     */
    public void resetForDocument(PDDocument newDoc) {
        final Point savedThumbViewPosition = new Point(getViewport().getViewPosition());
        ThumbnailButton[] oldButtons = this.thumbnailButtons;
        int newPages = newDoc.getNumberOfPages();
        ImageIcon[] reuseIcons = null;
        if (oldButtons != null && oldButtons.length == newPages) {
            reuseIcons = new ImageIcon[newPages];
            for (int i = 0; i < newPages; i++) {
                ThumbnailButton ob = oldButtons[i];
                if (ob != null && ob.hasRealThumb() && ob.getIcon() instanceof ImageIcon) {
                    Image im = ((ImageIcon) ob.getIcon()).getImage();
                    if (im != null) {
                        reuseIcons[i] = new ImageIcon(im);
                    }
                }
            }
        }

        thumbnailGenSuspended = true;
        if (thumbnailViewportListener != null) {
            getViewport().removeChangeListener(thumbnailViewportListener);
            thumbnailViewportListener = null;
        }
        ensureCenteringRowAttached();
        syncThumbRenderSizeToViewport(false);
        thumbnailBox.removeAll();
        this.document = newDoc;
        this.thumbnailRenderer = new PDFRenderer(newDoc);
        this.thumbnailButtons = new ThumbnailButton[newPages];
        populateThumbnailButtons(reuseIcons);
        thumbnailViewportListener = new thumbnailGenControl();
        getViewport().addChangeListener(thumbnailViewportListener);
        thumbnailGenSuspended = false;
        revalidate();
        SwingUtilities.invokeLater(() -> afterThumbnailDocumentReset(savedThumbViewPosition));
    }

    /**
     * After rebuild, the viewport often does not fire {@link ChangeListener}; without this,
     * placeholders would stay until the user scrolls. Runs after layout ({@code invokeLater}).
     */
    private void notifyThumbnailsViewportChanged() {
        if (thumbnailGenSuspended || thumbnailViewportListener == null) {
            return;
        }
        validate();
        thumbnailViewportListener.stateChanged(new ChangeEvent(getViewport()));
    }

    private void afterThumbnailDocumentReset(Point savedViewPosition) {
        notifyThumbnailsViewportChanged();
        restoreThumbnailScrollPosition(savedViewPosition);
    }

    private void restoreThumbnailScrollPosition(Point target) {
        if (target == null) {
            return;
        }
        validate();
        JViewport vp = getViewport();
        Component view = vp.getView();
        if (view == null) {
            return;
        }
        Dimension extent = vp.getExtentSize();
        Dimension viewSize = view.getSize();
        int maxX = max(0, viewSize.width - extent.width);
        int maxY = max(0, viewSize.height - extent.height);
        int x = min(max(target.x, 0), maxX);
        int y = min(max(target.y, 0), maxY);
        vp.setViewPosition(new Point(x, y));
    }

    void setThumbnailGenSuspended(boolean suspended) {
        this.thumbnailGenSuspended = suspended;
    }

    /**
     * @param reuseRealThumbs per-index icons to keep after reopen (same page count); null uses placeholders for all
     */
    private void populateThumbnailButtons(ImageIcon[] reuseRealThumbs) {
        ImageIcon nothumb = createScaledPlaceholder();
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            if (pageIndex > 0) {
                thumbnailBox.add(Box.createVerticalStrut(THUMB_VERTICAL_GAP));
            }
            ThumbnailButton tb = new ThumbnailButton(pageIndex + 1);
            thumbnailButtons[pageIndex] = tb;
            tb.setOpaque(true);
            tb.setBackground(Color.WHITE);
            tb.setMargin(new Insets(0, 0, 0, 0));
            tb.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(THUMB_CARD_BORDER, THUMB_CARD_LINE),
                    BorderFactory.createEmptyBorder(
                            THUMB_CARD_INSET_TOP, THUMB_CARD_INSET_LEFT,
                            THUMB_CARD_INSET_BOTTOM, THUMB_CARD_INSET_RIGHT)));
            tb.setFocusPainted(false);
            tb.setHorizontalAlignment(SwingConstants.CENTER);
            tb.setVerticalTextPosition(AbstractButton.BOTTOM);
            tb.setHorizontalTextPosition(AbstractButton.CENTER);
            if (reuseRealThumbs != null && pageIndex < reuseRealThumbs.length
                    && reuseRealThumbs[pageIndex] != null) {
                tb.setThumb(reuseRealThumbs[pageIndex], true);
            } else {
                tb.setThumb(nothumb, false);
            }
            if (tb.hasRealThumb() && !iconApproximatelyMatchesThumb(tb.getIcon())) {
                tb.setThumb(nothumb, false);
            }
            applyButtonThumbSize(tb);
            thumbnailBox.add(tb);
        }
    }

    /**
     * Get the Swing Box which contains the thumbnails.
     *
     * @return The Swing Box.
     */
    public Box getThumbnailBox() {
        return thumbnailBox;
    }

    /**
     * Return the array of all the thumbnail buttons.
     *
     * @return the thumbnail button array
     */
    public ThumbnailButton[] getThumbnailButtons() {
        return thumbnailButtons;
    }

    private static void configureThumbnailGraphics(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    }

    private static BufferedImage toBufferedImage(Image img) {
        if (img == null) {
            return null;
        }
        if (img instanceof BufferedImage) {
            return (BufferedImage) img;
        }
        int w = img.getWidth(null);
        int h = img.getHeight(null);
        if (w <= 0 || h <= 0) {
            return null;
        }
        BufferedImage bi = new BufferedImage(w, h, TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        try {
            configureThumbnailGraphics(g);
            g.setBackground(Color.WHITE);
            g.clearRect(0, 0, w, h);
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return bi;
    }

    private BufferedImage scaleImageHighQuality(BufferedImage src, int dw, int dh) {
        BufferedImage dst = new BufferedImage(dw, dh, TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            configureThumbnailGraphics(g);
            g.setBackground(Color.WHITE);
            g.clearRect(0, 0, dw, dh);
            g.drawImage(src, 0, 0, dw, dh, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private BufferedImage embedLandscapeInSquare(BufferedImage wideStrip, int ts) {
        BufferedImage square = new BufferedImage(ts, ts, TYPE_INT_RGB);
        Graphics2D g = square.createGraphics();
        try {
            configureThumbnailGraphics(g);
            g.setBackground(Color.WHITE);
            g.clearRect(0, 0, ts, ts);
            int nh = wideStrip.getHeight();
            int top = (ts - nh) / 2;
            g.drawImage(wideStrip, 0, top, null);
            g.setColor(Color.black);
            if (top > 0) {
                g.drawLine(0, top - 1, ts - 1, top - 1);
            }
            if (top + nh < ts) {
                g.drawLine(0, top + nh, ts - 1, top + nh);
            }
        } finally {
            g.dispose();
        }
        return square;
    }

    /**
     * Renders a page preview: supersampled PDF render, then high-quality resize to the sidebar thumb size.
     */
    private Image getThumb(int pIndex) {
        PDPage page = document.getPage(pIndex);
        BufferedImage rawFull = toBufferedImage(getStoredThumbnail(pIndex, page));
        if (rawFull == null) {
            rawFull = renderThumbnailBuffered(pIndex, page);
        }
        if (rawFull == null) {
            return null;
        }
        int sw = rawFull.getWidth();
        int sh = rawFull.getHeight();
        if (sw <= 0 || sh <= 0) {
            return null;
        }
        int ts = thumbRenderPixels;
        if (sh >= sw) {
            int nh = ts;
            int nw = max(1, (int) Math.round(sw * (double) ts / sh));
            return scaleImageHighQuality(rawFull, nw, nh);
        }
        int nw = ts;
        int nh = max(1, (int) Math.round(sh * (double) ts / sw));
        BufferedImage scaled = scaleImageHighQuality(rawFull, nw, nh);
        return embedLandscapeInSquare(scaled, ts);
    }

    /**
     * Get a stored thumbnail from a PDF page.
     *
     * @param pIndex the PDF page index
     * @return the thumbnail image, or null
     */
    private Image getStoredThumbnail(int pIndex, PDPage page) {
        Image thumbnail = null;

        // Get the thumbnail PDF stream
        COSStream strm = page.getCOSObject().getCOSStream(COSName.THUMB);

        // If a thumbnail stream can be found, try to extract the image
        if (strm != null) {
            try {
                thumbnail = PDImageXObject.createThumbnail(strm).getImage();
            } catch (IOException e) {
                thumbnail = null;
            }
        }

        // Return the found thumbnail or null, as the case may be.
        return thumbnail;
    }

    /**
     * Renders page {@code pIndex} at higher resolution then scaled down for crisp thumbnails.
     */
    private BufferedImage renderThumbnailBuffered(int pIndex, PDPage page) {
        PDRectangle rect = page.getCropBox();
        if (rect == null) {
            rect = page.getMediaBox();
        }
        if (rect == null) {
            return null;
        }
        float ts = thumbRenderPixels;
        float hscale = ts / rect.getWidth();
        float wscale = ts / rect.getHeight();
        float scale = min(hscale, wscale) * THUMB_RENDER_SUPERSAMPLE;
        try {
            return thumbnailRenderer.renderImage(pIndex, scale);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Responds to a change in the displayed PDF page, making the appropriate
     * thumbnail visible in the thumbnails windows.
     *
     * ENH: make this put the thumbnail reliably at the top of the thumbnail
     * pane.
     *
     * @param e the event fired when the page is changed in the
     * JPDFBoxViewPanel.
     */
    @Override
    public void pageChanged(PageChangedEvent e) {
        scrollPageThumbIntoView(e.getCurrentPage());
    }

    /**
     * Scrolls the thumbnail list so the button for {@code pageOneBased} is inside the viewport.
     * Queued on the EDT so layout ({@code centeringRow}, struts) is up to date before converting coordinates.
     */
    public void scrollPageThumbIntoView(int pageOneBased) {
        if (pageOneBased < 1) {
            return;
        }
        SwingUtilities.invokeLater(() -> scrollPageThumbIntoViewNow(pageOneBased));
    }

    private void scrollPageThumbIntoViewNow(int pageOneBased) {
        if (thumbnailGenSuspended || thumbnailButtons == null || thumbnailButtons.length == 0) {
            return;
        }
        int idx = pageOneBased - 1;
        if (idx < 0 || idx >= thumbnailButtons.length) {
            return;
        }
        ThumbnailButton tb = thumbnailButtons[idx];
        JViewport vp = getViewport();
        Component view = vp.getView();
        if (view == null || tb.getParent() == null) {
            return;
        }
        if (!SwingUtilities.isDescendingFrom(tb, this)) {
            return;
        }
        validate();
        view.validate();
        Rectangle viewRect = vp.getViewRect();
        Rectangle tbInView = SwingUtilities.convertRectangle(tb.getParent(), tb.getBounds(), view);
        if (viewRect.contains(tbInView)) {
            return;
        }
        vp.scrollRectToVisible(tbInView);
    }

    /**
     * Generates the thumbnails for the ThumbnailButton objects. It is invoked
     * whenever the ThumbnailsPane changes size, position, or extent size.
     * Thumbnail generation is a relatively slow operation, so this only
     * generates thumbnails as the ThumbnailButton objects become visible.
     */
    private static class thumbnailGenControl implements ChangeListener {

        /**
         * The constructor of thumbnailGenControl, which is currently empty.
         */
        public thumbnailGenControl() {
        }

        /**
         * ThumbnailsPane has changed size; generate thumbnails as required.
         *
         * @param e the size change event
         */
        @Override
        public void stateChanged(ChangeEvent e) {
            JViewport vp = (JViewport) e.getSource();
            ThumbnailsPane tp = (ThumbnailsPane) vp.getParent();
            if (tp.thumbnailGenSuspended) {
                return;
            }
            if (tp.thumbnailButtons == null) {
                return;
            }
            // if there's no Box yet in the JViewport, there's nothing to do, return.
            if (vp.getView() == null) {
                return;
            }
            tp.syncThumbRenderSizeToViewport(true);
            // Get the visible rectangle of the JViewport, in internal coordinates
            Rectangle viewRect = vp.getViewRect();
            int topView = viewRect.y;                                  // Top of the viewport
            int botView = viewRect.y + viewRect.height;     // Bottom of the viewport
            /**
             * For each button, decide if the button is visible. If it is, and
             * it doesn't yet have a thumbnail, generate one. Stop after the
             * last visible thumbnail button has been processed. The code is
             * confusing enough that it is extensively commented. It does seem
             * to work, but may have problems with edge cases.
             */
            for (ThumbnailButton tb : tp.thumbnailButtons) {
                /**
                 * If we're past the first page of the document, and the
                 * thumbnail still shows a y position of zero, the button isn't
                 * actually visible yet; don't generate a thumbnail for it.
                 *
                 * This hack will generate a thumbnail for the first page when
                 * it is not needed, but that is acceptable and beats trying to
                 * figure out how to test for visibility.
                 */
                if (tb.getPageNum() > 1 && tb.getY() == 0) {
                    break;
                }
                // Get the top and bottom of the button.
                int topTb = tb.getY();
                int botTb = tb.getY() + tb.getHeight();
                /**
                 * If the top of the button is below the bottom of the visible
                 * area of the pane, we're past the last visible button; stop
                 * looking.
                 */
                if (topTb > botView) {
                    break;
                }
                /**
                 * If the bottom of the button is before the top of the visible
                 * area of the pane, skip the button.
                 */
                if (botTb < topView) {
                    continue;
                }

                // if the button already has a real thumbnail, skip it.
                if (tb.hasRealThumb()) {
                    continue;
                }
                /**
                 * The button is visible and does not have a real thumbnail.
                 * Generate a thumbnail and assign it to the button.
                 */
                Image thumb = tp.getThumb(tb.getPageNum() - 1);
                if (thumb != null) {
                    tb.setThumb(new ImageIcon(thumb), true);
                }
            }
        }
    }
}
