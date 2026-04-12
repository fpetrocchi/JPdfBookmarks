package it.flavianopetrocchi.jpdfbookmarks;

import it.flavianopetrocchi.components.collapsingpanel.CollapsingPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;

/**
 * Pannello sinistro della finestra principale: strip verticale di icone (segnalibri / miniature) e area contenuti.
 *
 * @author fla
 */
public class LeftPanel extends CollapsingPanel {

    /**
     * Come aggiornare il contenitore delle miniature (repaint / sostituzione solo se serve).
     */
    public enum ThumbnailsUpdateMode {
        /** Rimuove il contenuto precedente, aggiunge il nuovo {@link JScrollPane} (se non null), revalidate/repaint. */
        REPLACE_AND_REPAINT,
        /**
         * Dopo salvataggio: stesso swap del contenuto ma senza {@code repaint()} esplicito (solo {@code revalidate})
         * per ridurre il lampo; nessun aggiornamento se il figlio mostrato è già {@code thumbnails}.
         */
        REPLACE_AFTER_SAVE,
        /** Non modificare il pannello (es. durante salvataggio in-place per evitare lampo). */
        PRESERVE_DURING_SAVE
    }

    JPanel bookmarksPanel;
    JPanel thumbnailsPanel;
    JScrollPane voidScrollPane = new JScrollPane();

    public LeftPanel(JSplitPane splitter) {
        super(splitter);
    }

    public void addBookmarksPanel(JPanel bookmarksPanel) {
        this.bookmarksPanel = bookmarksPanel;
        addInnerPanel(
                bookmarksPanel,
                Res.getString("BOOKMARKS_TAB_TITLE"),
                createBookmarksSidebarIcon(),
                Res.getString("SHOW_BOOKMARKS"));
    }

    public void addThumbnailsPanel(JPanel thumbnailsPanel) {
        this.thumbnailsPanel = thumbnailsPanel;
        this.thumbnailsPanel.setLayout(new BorderLayout());
        addInnerPanel(
                this.thumbnailsPanel,
                Res.getString("THUMBNAILS_TAB_TITLE"),
                createThumbnailsSidebarIcon(),
                Res.getString("SHOW_THUMBNAILS"));
    }

    public void updateThumbnails(JScrollPane thumbnails, ThumbnailsUpdateMode mode) {
        if (mode == ThumbnailsUpdateMode.PRESERVE_DURING_SAVE) {
            return;
        }
        boolean afterSave = mode == ThumbnailsUpdateMode.REPLACE_AFTER_SAVE;
        if (afterSave && thumbnails != null && thumbnailsPanel.getComponentCount() == 1
                && thumbnailsPanel.getComponent(0) == thumbnails) {
            return;
        }
        thumbnailsPanel.removeAll();
        if (thumbnails != null) {
            thumbnailsPanel.add(thumbnails, BorderLayout.CENTER);
        }
        thumbnailsPanel.revalidate();
        if (!afterSave) {
            thumbnailsPanel.repaint();
        }
    }

    public void selectPanelToShow(String name) {
        setSelectedInnerPanelName(name);
    }

    private static Icon createBookmarksSidebarIcon() {
        int s = 22;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(52, 98, 168));
            int[] xs = {4, 18, 18, 11, 11};
            int[] ys = {3, 3, 19, 13, 19};
            g.fillPolygon(xs, ys, 5);
            g.setColor(new Color(255, 255, 255, 220));
            g.fillRect(7, 6, 8, 2);
            g.fillRect(7, 10, 6, 2);
        } finally {
            g.dispose();
        }
        return new ImageIcon(img);
    }

    private static Icon createThumbnailsSidebarIcon() {
        int s = 22;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(52, 98, 168));
            g.setStroke(new BasicStroke(1.4f));
            int m = 4;
            int half = (s - 2 * m) / 2;
            g.drawRoundRect(m, m, half - 1, half - 1, 3, 3);
            g.drawRoundRect(m + half, m, half - 1, half - 1, 3, 3);
            g.drawRoundRect(m, m + half, half - 1, half - 1, 3, 3);
            g.drawRoundRect(m + half, m + half, half - 1, half - 1, 3, 3);
        } finally {
            g.dispose();
        }
        return new ImageIcon(img);
    }
}
