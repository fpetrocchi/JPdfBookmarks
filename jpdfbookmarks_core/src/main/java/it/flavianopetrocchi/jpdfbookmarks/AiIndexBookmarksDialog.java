package it.flavianopetrocchi.jpdfbookmarks;

import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiModelConverter;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiOrchestrationException;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiOrchestrator;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.ProcessIndexResult;
import it.flavianopetrocchi.jpdfbookmarks.bookmark.Bookmark;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.text.MessageFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Dialogo per estrarre segnalibri da immagini dell'indice tramite IA (rendering + orchestrator).
 * <p>
 * Non modale: l'utente può scorrere il PDF nella finestra principale mentre imposta l'intervallo di pagine.
 */
public class AiIndexBookmarksDialog extends JDialog {

    /**
     * Ultimi valori di pagina inizio/fine e offset scelti dall'utente (per riproporli alla prossima apertura).
     */
    public record AiIndexDialogMemory(int startPage, int endPage, int offset) {}

    private static final int OFFSET_SPINNER_MIN = -10_000;
    private static final int OFFSET_SPINNER_MAX = 10_000;
    private static final int MERGE_OPTION_APPEND = 0;
    private static final int MERGE_OPTION_REPLACE = 1;
    private static final int MERGE_OPTION_CANCEL = 2;

    private final Frame ownerFrame;
    private final PDDocument document;
    private final AiOrchestrator orchestrator;
    private final BiConsumer<List<Bookmark>, Boolean> bookmarkApplicator;
    private final Prefs userPrefs;

    private final IPdfView pdfView;
    private final Consumer<AiIndexDialogMemory> onEndStoreValues;

    private JSpinner spinnerStartPage;
    private JSpinner spinnerEndPage;
    private JSpinner spinnerOffset;
    private JProgressBar progressBar;
    private JButton btnAnalyze;
    private JButton btnClose;
    private JButton btnSetStartFromView;
    private JButton btnSetEndFromView;
    private SwingWorker<ProcessIndexResult, Void> activeWorker;

    /**
     * @param bookmarkApplicator riceve i segnalibri convertiti e {@code true} per sostituire l'intero albero,
     *                          {@code false} per aggiungerli in coda sotto la radice (stesso schema di Incolla)
     * @param prefs              preferenze (per allineamento API con la finestra principale)
     * @param pdfView            visualizzatore PDF per il pulsante "pagina corrente"; può essere {@code null}
     * @param previousValues     se non {@code null}, inizializza i campi con questi valori (adeguati al numero di pagine)
     * @param onEndStoreValues   chiamato alla chiusura del dialogo con i valori correnti dei campi (può essere {@code null})
     */
    public AiIndexBookmarksDialog(
            Frame owner,
            PDDocument document,
            int numPages,
            AiOrchestrator orchestrator,
            BiConsumer<List<Bookmark>, Boolean> bookmarkApplicator,
            Prefs prefs,
            IPdfView pdfView,
            AiIndexDialogMemory previousValues,
            Consumer<AiIndexDialogMemory> onEndStoreValues) {
        super(owner, false);
        this.ownerFrame = Objects.requireNonNull(owner, "owner");
        this.document = document;
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.bookmarkApplicator = Objects.requireNonNull(bookmarkApplicator, "bookmarkApplicator");
        this.userPrefs = Objects.requireNonNull(prefs, "prefs");
        this.pdfView = pdfView;
        this.onEndStoreValues = onEndStoreValues;

        setTitle(Res.getString("AI_INDEX_DIALOG_TITLE"));

        JPanel content = (JPanel) getContentPane();
        content.setLayout(new BorderLayout(0, 14));
        content.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JLabel intro = new JLabel(htmlWrap(Res.getString("AI_INDEX_INTRO"), 480));
        intro.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        Font baseFont = intro.getFont();
        intro.setFont(baseFont.deriveFont(baseFont.getSize2D() + 0.5f));
        JLabel destNote = new JLabel(htmlWrap(Res.getString("AI_INDEX_DESTINATION_NOTE"), 480));
        destNote.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        destNote.setFont(baseFont.deriveFont(Math.max(11f, baseFont.getSize2D() - 0.5f)));
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(false);
        north.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(intro);
        north.add(Box.createVerticalStrut(10));
        north.add(destNote);
        if (orchestrator.usesCloudService()) {
            north.add(Box.createVerticalStrut(10));
            JLabel cloudNote =
                    new JLabel(
                            htmlWrap(
                                    MessageFormat.format(
                                            Res.getString("AI_INDEX_CLOUD_PREVIEW_NOTE"),
                                            Prefs.CLOUD_FREE_PREVIEW_MAX_INDEX_PAGES),
                                    480));
            cloudNote.setAlignmentX(JLabel.LEFT_ALIGNMENT);
            cloudNote.setFont(baseFont.deriveFont(Math.max(11f, baseFont.getSize2D() - 0.5f)));
            north.add(cloudNote);
        }
        content.add(north, BorderLayout.NORTH);

        int maxPage = Math.max(1, numPages);

        int initialStart = 1;
        int initialEnd = maxPage;
        int initialOffset = 0;
        if (previousValues != null) {
            initialStart = clamp(previousValues.startPage(), 1, maxPage);
            initialEnd = clamp(previousValues.endPage(), 1, maxPage);
            if (initialStart > initialEnd) {
                initialEnd = maxPage;
                initialStart = Math.min(initialStart, initialEnd);
            }
            initialOffset =
                    clamp(
                            previousValues.offset(),
                            OFFSET_SPINNER_MIN,
                            OFFSET_SPINNER_MAX);
        }

        spinnerStartPage = new JSpinner(new SpinnerNumberModel(initialStart, 1, maxPage, 1));
        spinnerEndPage = new JSpinner(new SpinnerNumberModel(initialEnd, 1, maxPage, 1));
        spinnerOffset = new JSpinner(new SpinnerNumberModel(initialOffset, OFFSET_SPINNER_MIN, OFFSET_SPINNER_MAX, 1));

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBorder(BorderFactory.createTitledBorder(Res.getString("AI_INDEX_SECTION_RANGE")));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8);
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        c.gridx = 0;
        c.gridy = 0;
        fields.add(
                buildFieldDescription(
                        Res.getString("AI_INDEX_START_PAGE"),
                        Res.getString("AI_INDEX_START_PAGE_HINT")),
                c);
        c.gridx = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.LINE_END;
        fields.add(wrapSpinnerWithCurrentPageButton(spinnerStartPage, true), c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        fields.add(
                buildFieldDescription(
                        Res.getString("AI_INDEX_END_PAGE"), Res.getString("AI_INDEX_END_PAGE_HINT")),
                c);
        c.gridx = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.LINE_END;
        fields.add(wrapSpinnerWithCurrentPageButton(spinnerEndPage, false), c);

        c.gridx = 0;
        c.gridy = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        fields.add(
                buildFieldDescription(
                        Res.getString("AI_INDEX_PAGE_OFFSET"),
                        Res.getString("AI_INDEX_PAGE_OFFSET_HINT")),
                c);
        c.gridx = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.LINE_END;
        fields.add(spinnerOffset, c);

        content.add(fields, BorderLayout.CENTER);

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.add(progressBar);
        south.add(Box.createVerticalStrut(8));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        btnAnalyze = new JButton(Res.getString("AI_INDEX_ANALYZE"));
        btnAnalyze.addActionListener(this::onAnalyzeClicked);
        btnClose = new JButton(Res.getString("AI_INDEX_CLOSE"));
        btnClose.addActionListener(this::onCloseClicked);
        buttons.add(btnAnalyze);
        buttons.add(btnClose);
        south.add(buttons);
        content.add(south, BorderLayout.SOUTH);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        onCloseClicked(null);
                    }
                });

        pack();
        Dimension d = getSize();
        int minW = 560;
        int minH = Math.max(d.height, 360);
        setMinimumSize(new Dimension(minW, minH));
        if (d.width < minW || d.height < minH) {
            setSize(new Dimension(Math.max(d.width, minW), Math.max(d.height, minH)));
        }
        setLocationRelativeTo(owner);
    }

    private static String htmlWrap(String body, int widthPx) {
        return "<html><body style='width:" + widthPx + "px'>" + body + "</body></html>";
    }

    private static JPanel buildFieldDescription(String title, String hintHtmlBody) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        Font tf = titleLabel.getFont();
        titleLabel.setFont(tf.deriveFont(Font.BOLD, tf.getSize2D() + 0.5f));
        titleLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        p.add(titleLabel);

        JLabel hint = new JLabel(htmlWrap(hintHtmlBody, 360));
        Color hintColor = UIManager.getColor("Label.disabledForeground");
        hint.setForeground(hintColor != null ? hintColor : new Color(0x55, 0x55, 0x55));
        Font hf = hint.getFont();
        hint.setFont(hf.deriveFont(Math.max(11f, hf.getSize2D() - 0.5f)));
        hint.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        p.add(Box.createVerticalStrut(4));
        p.add(hint);

        return p;
    }

    private JPanel wrapSpinnerWithCurrentPageButton(JSpinner spinner, boolean forStart) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        row.setOpaque(false);
        row.add(spinner);
        JButton b = new JButton(createCurrentPageIcon());
        b.setMargin(new Insets(2, 4, 2, 4));
        b.setToolTipText(
                forStart
                        ? Res.getString("AI_INDEX_SET_CURRENT_START_TIP")
                        : Res.getString("AI_INDEX_SET_CURRENT_END_TIP"));
        b.addActionListener(e -> applyViewerPageToSpinner(spinner));
        b.setEnabled(pdfView != null);
        if (forStart) {
            btnSetStartFromView = b;
        } else {
            btnSetEndFromView = b;
        }
        row.add(b);
        return row;
    }

    private static Icon createCurrentPageIcon() {
        int s = 16;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(52, 120, 180));
            int pad = 2;
            g.drawOval(pad, pad, s - 1 - 2 * pad, s - 1 - 2 * pad);
            int cx = s / 2;
            int cy = s / 2;
            g.drawLine(cx, pad + 1, cx, s - pad - 2);
            g.drawLine(pad + 1, cy, s - pad - 2, cy);
        } finally {
            g.dispose();
        }
        return new ImageIcon(img);
    }

    private void applyViewerPageToSpinner(JSpinner spinner) {
        if (pdfView == null) {
            JOptionPane.showMessageDialog(
                    jOptionPaneParent(),
                    Res.getString("AI_INDEX_SET_CURRENT_NO_VIEW"),
                    Res.getString("AI_INDEX_DIALOG_TITLE"),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int page = pdfView.getPageNumber();
        if (page < 1) {
            JOptionPane.showMessageDialog(
                    jOptionPaneParent(),
                    Res.getString("AI_INDEX_SET_CURRENT_NO_PAGE"),
                    Res.getString("AI_INDEX_DIALOG_TITLE"),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SpinnerNumberModel m = (SpinnerNumberModel) spinner.getModel();
        int lo = ((Number) m.getMinimum()).intValue();
        int hi = ((Number) m.getMaximum()).intValue();
        int clamped = clamp(page, lo, hi);
        spinner.setValue(clamped);
        if (clamped != page) {
            JOptionPane.showMessageDialog(
                    jOptionPaneParent(),
                    String.format(Res.getString("AI_INDEX_SET_CURRENT_CLAMPED"), page, clamped),
                    Res.getString("AI_INDEX_DIALOG_TITLE"),
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private Component jOptionPaneParent() {
        return (isDisplayable() && isShowing()) ? this : ownerFrame;
    }

    private void onCloseClicked(ActionEvent e) {
        storeCurrentValuesForNextOpen();
        if (activeWorker != null && !activeWorker.isDone()) {
            activeWorker.cancel(true);
        }
        dispose();
    }

    private void storeCurrentValuesForNextOpen() {
        if (onEndStoreValues == null) {
            return;
        }
        onEndStoreValues.accept(
                new AiIndexDialogMemory(
                        (Integer) spinnerStartPage.getValue(),
                        (Integer) spinnerEndPage.getValue(),
                        (Integer) spinnerOffset.getValue()));
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private void onAnalyzeClicked(ActionEvent e) {
        onAnalyze();
    }

    public void onAnalyze() {
        if (document == null) {
            JOptionPane.showMessageDialog(
                    jOptionPaneParent(),
                    Res.getString("AI_INDEX_NO_DOCUMENT"),
                    Res.getString("AI_INDEX_DIALOG_TITLE"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (activeWorker != null && !activeWorker.isDone()) {
            return;
        }

        int start = (Integer) spinnerStartPage.getValue();
        int end = (Integer) spinnerEndPage.getValue();
        final int userChosenIndexStart = start;
        final int userChosenIndexEnd = end;
        if (start > end) {
            JOptionPane.showMessageDialog(
                    jOptionPaneParent(),
                    Res.getString("AI_INDEX_BAD_RANGE"),
                    Res.getString("AI_INDEX_DIALOG_TITLE"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (orchestrator.usesCloudService()) {
            int maxFree = Prefs.CLOUD_FREE_PREVIEW_MAX_INDEX_PAGES;
            int span = end - start + 1;
            if (span > maxFree) {
                int newEnd = start + maxFree - 1;
                int opt =
                        JOptionPane.showConfirmDialog(
                                jOptionPaneParent(),
                                MessageFormat.format(
                                        Res.getString("AI_INDEX_CLOUD_PAGE_LIMIT_CONFIRM"),
                                        span,
                                        maxFree,
                                        start,
                                        newEnd),
                                Res.getString("AI_INDEX_DIALOG_TITLE"),
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE);
                if (opt != JOptionPane.YES_OPTION) {
                    return;
                }
                end = newEnd;
                spinnerEndPage.setValue(end);
            }
        }

        final int runStart = start;
        final int runEnd = end;
        final int offset = (Integer) spinnerOffset.getValue();

        setFormEnabled(false);
        progressBar.setVisible(true);
        revalidate();

        activeWorker =
                new SwingWorker<ProcessIndexResult, Void>() {
                    @Override
                    protected ProcessIndexResult doInBackground() throws Exception {
                        return orchestrator.processIndex(document, runStart, runEnd);
                    }

                    @Override
                    protected void done() {
                        progressBar.setVisible(false);
                        setFormEnabled(true);
                        activeWorker = null;

                        if (isCancelled()) {
                            return;
                        }
                        try {
                            ProcessIndexResult indexResult = get();
                            if (orchestrator.usesCloudService() && indexResult.isCloudSubmit()) {
                                String tid =
                                        indexResult.getCloudTaskId() != null
                                                ? indexResult.getCloudTaskId().trim()
                                                : "";
                                storeCurrentValuesForNextOpen();
                                dispose();
                                new AiPreviewDialog(
                                                ownerFrame,
                                                AiPreviewPaymentConfig.fromPrefs(userPrefs),
                                                tid,
                                                indexResult.getBookmarks(),
                                                aiBookmarks ->
                                                        handleCloudBookmarksReady(aiBookmarks, offset),
                                                pdfView,
                                                offset,
                                                document,
                                                orchestrator,
                                                userChosenIndexStart,
                                                userChosenIndexEnd)
                                        .setVisible(true);
                                return;
                            }
                            List<AiBookmark> result = indexResult.getBookmarks();
                            List<Bookmark> converted = AiModelConverter.toAppBookmarks(result, offset);
                            if (converted.isEmpty()) {
                                JOptionPane.showMessageDialog(
                                        jOptionPaneParent(),
                                        Res.getString("AI_INDEX_NO_BOOKMARKS_EXTRACTED"),
                                        Res.getString("AI_INDEX_DIALOG_TITLE"),
                                        JOptionPane.INFORMATION_MESSAGE);
                                return;
                            }

                            showMergeChoiceAndApplyBookmarks(converted);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } catch (ExecutionException ex) {
                            Throwable cause = ex.getCause();
                            if (cause instanceof AiOrchestrationException) {
                                JOptionPane.showMessageDialog(
                                        jOptionPaneParent(),
                                        cause.getMessage(),
                                        Res.getString("AI_INDEX_ERROR_TITLE"),
                                        JOptionPane.ERROR_MESSAGE);
                            } else {
                                String msg =
                                        cause != null
                                                ? cause.getMessage()
                                                : Res.getString("AI_INDEX_UNEXPECTED_ERROR");
                                JOptionPane.showMessageDialog(
                                        jOptionPaneParent(),
                                        msg,
                                        Res.getString("AI_INDEX_ERROR_TITLE"),
                                        JOptionPane.ERROR_MESSAGE);
                            }
                        }
                    }
                };
        activeWorker.execute();
    }

    private void handleCloudBookmarksReady(List<AiBookmark> aiBookmarks, int offset) {
        if (aiBookmarks == null || aiBookmarks.isEmpty()) {
            JOptionPane.showMessageDialog(
                    jOptionPaneParent(),
                    Res.getString("AI_PREVIEW_FETCH_EMPTY"),
                    Res.getString("AI_INDEX_DIALOG_TITLE"),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<Bookmark> converted = AiModelConverter.toAppBookmarks(aiBookmarks, offset);
        if (converted.isEmpty()) {
            JOptionPane.showMessageDialog(
                    jOptionPaneParent(),
                    Res.getString("AI_INDEX_NO_BOOKMARKS_EXTRACTED"),
                    Res.getString("AI_INDEX_DIALOG_TITLE"),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        showMergeChoiceAndApplyBookmarks(converted);
    }

    private void showMergeChoiceAndApplyBookmarks(List<Bookmark> bookmarks) {
        String[] options =
                new String[] {
                    Res.getString("AI_INDEX_MERGE_APPEND"),
                    Res.getString("AI_INDEX_MERGE_REPLACE"),
                    Res.getString("CANCEL")
                };
        int choice =
                JOptionPane.showOptionDialog(
                        jOptionPaneParent(),
                        Res.getString("AI_INDEX_MERGE_PROMPT"),
                        Res.getString("AI_INDEX_DIALOG_TITLE"),
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        options,
                        options[MERGE_OPTION_APPEND]);

        if (choice == JOptionPane.CLOSED_OPTION || choice == MERGE_OPTION_CANCEL) {
            return;
        }
        boolean replace = (choice == MERGE_OPTION_REPLACE);
        bookmarkApplicator.accept(bookmarks, replace);
    }

    private void setFormEnabled(boolean enabled) {
        spinnerStartPage.setEnabled(enabled);
        spinnerEndPage.setEnabled(enabled);
        spinnerOffset.setEnabled(enabled);
        btnAnalyze.setEnabled(enabled);
        boolean viewBtns = enabled && pdfView != null;
        if (btnSetStartFromView != null) {
            btnSetStartFromView.setEnabled(viewBtns);
        }
        if (btnSetEndFromView != null) {
            btnSetEndFromView.setEnabled(viewBtns);
        }
    }
}
