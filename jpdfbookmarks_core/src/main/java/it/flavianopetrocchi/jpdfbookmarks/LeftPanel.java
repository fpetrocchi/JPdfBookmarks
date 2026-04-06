package it.flavianopetrocchi.jpdfbookmarks;

import it.flavianopetrocchi.components.collapsingpanel.CollapsingPanel;
import java.awt.BorderLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;

/**
 * Left-hand panel of the main window, displaying alternately the document
 * outline (bookmarks) or the document thumbnails.
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
        addInnerPanel(bookmarksPanel, Res.getString("BOOKMARKS_TAB_TITLE"));
    }

    public void addThumbnailsPanel(JPanel thumbnailsPanel) {
        this.thumbnailsPanel = thumbnailsPanel;
        this.thumbnailsPanel.setLayout(new BorderLayout());
        addInnerPanel(this.thumbnailsPanel, Res.getString("THUMBNAILS_TAB_TITLE"));
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
        getCardLayout().show(getCardsContainerPanel(), name);
        getComboBoxSelector().setSelectedItem(name);
    }
}
