package it.flavianopetrocchi.jpdfbookmarks;

import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiModelConverter;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiOrchestrationException;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiOrchestrator;
import it.flavianopetrocchi.jpdfbookmarks.bookmark.Bookmark;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Dialogo per estrarre segnalibri da immagini dell'indice tramite IA (rendering + orchestrator).
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

    private final PDDocument document;
    private final AiOrchestrator orchestrator;
    private final BiConsumer<List<Bookmark>, Boolean> bookmarkApplicator;
    private final Consumer<AiIndexDialogMemory> onEndStoreValues;

    private JSpinner spinnerStartPage;
    private JSpinner spinnerEndPage;
    private JSpinner spinnerOffset;
    private JProgressBar progressBar;
    private JButton btnAnalyze;
    private JButton btnClose;
    private SwingWorker<List<AiBookmark>, Void> activeWorker;

    /**
     * @param bookmarkApplicator riceve i segnalibri convertiti e {@code true} per sostituire l'intero albero,
     *                          {@code false} per aggiungerli in coda sotto la radice (stesso schema di Incolla)
     * @param previousValues     se non {@code null}, inizializza i campi con questi valori (adeguati al numero di pagine)
     * @param onEndStoreValues   chiamato alla chiusura del dialogo con i valori correnti dei campi (può essere {@code null})
     */
    public AiIndexBookmarksDialog(
            Frame owner,
            PDDocument document,
            int numPages,
            AiOrchestrator orchestrator,
            BiConsumer<List<Bookmark>, Boolean> bookmarkApplicator,
            AiIndexDialogMemory previousValues,
            Consumer<AiIndexDialogMemory> onEndStoreValues) {
        super(owner, true);
        this.document = document;
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.bookmarkApplicator = Objects.requireNonNull(bookmarkApplicator, "bookmarkApplicator");
        this.onEndStoreValues = onEndStoreValues;

        setTitle(Res.getString("AI_INDEX_DIALOG_TITLE"));

        JPanel content = (JPanel) getContentPane();
        content.setLayout(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        int maxPage = Math.max(1, numPages);
        JPanel fields = new JPanel(new GridLayout(3, 2, 8, 8));

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

        fields.add(new JLabel(Res.getString("AI_INDEX_START_PAGE") + ":"));
        spinnerStartPage = new JSpinner(new SpinnerNumberModel(initialStart, 1, maxPage, 1));
        fields.add(spinnerStartPage);

        fields.add(new JLabel(Res.getString("AI_INDEX_END_PAGE") + ":"));
        spinnerEndPage = new JSpinner(new SpinnerNumberModel(initialEnd, 1, maxPage, 1));
        fields.add(spinnerEndPage);

        fields.add(new JLabel(Res.getString("AI_INDEX_PAGE_OFFSET") + ":"));
        spinnerOffset = new JSpinner(new SpinnerNumberModel(initialOffset, OFFSET_SPINNER_MIN, OFFSET_SPINNER_MAX, 1));
        fields.add(spinnerOffset);

        content.add(fields, BorderLayout.NORTH);

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
        btnClose = new JButton(Res.getString("CANCEL"));
        btnClose.addActionListener(this::onCloseClicked);
        buttons.add(btnAnalyze);
        buttons.add(btnClose);
        south.add(buttons);
        content.add(south, BorderLayout.SOUTH);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onCloseClicked(null);
            }
        });

        pack();
        setLocationRelativeTo(owner);
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

    /**
     * Avvia l'analisi IA: disabilita i controlli, mostra la barra di avanzamento ed esegue
     * {@link AiOrchestrator#processIndex} in background.
     */
    public void onAnalyze() {
        if (document == null) {
            JOptionPane.showMessageDialog(
                    this,
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
        if (start > end) {
            JOptionPane.showMessageDialog(
                    this,
                    Res.getString("AI_INDEX_BAD_RANGE"),
                    Res.getString("AI_INDEX_DIALOG_TITLE"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        /* Offset letto dallo spinner nel momento del clic su Analizza (stesso valore usato in done() per la conversione). */
        final int offset = (Integer) spinnerOffset.getValue();

        setFormEnabled(false);
        progressBar.setVisible(true);
        revalidate();

        activeWorker =
                new SwingWorker<List<AiBookmark>, Void>() {
                    @Override
                    protected List<AiBookmark> doInBackground() throws Exception {
                        return orchestrator.processIndex(document, start, end);
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
                            List<AiBookmark> result = get();
                            List<Bookmark> converted = AiModelConverter.toAppBookmarks(result, offset);
                            if (converted.isEmpty()) {
                                JOptionPane.showMessageDialog(
                                        AiIndexBookmarksDialog.this,
                                        Res.getString("AI_INDEX_NO_BOOKMARKS_EXTRACTED"),
                                        Res.getString("AI_INDEX_DIALOG_TITLE"),
                                        JOptionPane.INFORMATION_MESSAGE);
                                return;
                            }

                            String[] options =
                                    new String[] {
                                        Res.getString("AI_INDEX_MERGE_APPEND"),
                                        Res.getString("AI_INDEX_MERGE_REPLACE"),
                                        Res.getString("CANCEL")
                                    };
                            int choice =
                                    JOptionPane.showOptionDialog(
                                            AiIndexBookmarksDialog.this,
                                            Res.getString("AI_INDEX_MERGE_PROMPT"),
                                            Res.getString("AI_INDEX_DIALOG_TITLE"),
                                            JOptionPane.DEFAULT_OPTION,
                                            JOptionPane.QUESTION_MESSAGE,
                                            null,
                                            options,
                                            options[MERGE_OPTION_APPEND]);

                            if (choice == JOptionPane.CLOSED_OPTION
                                    || choice == MERGE_OPTION_CANCEL) {
                                return;
                            }
                            boolean replace = (choice == MERGE_OPTION_REPLACE);
                            bookmarkApplicator.accept(converted, replace);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } catch (ExecutionException ex) {
                            Throwable cause = ex.getCause();
                            if (cause instanceof AiOrchestrationException) {
                                JOptionPane.showMessageDialog(
                                        AiIndexBookmarksDialog.this,
                                        cause.getMessage(),
                                        Res.getString("AI_INDEX_ERROR_TITLE"),
                                        JOptionPane.ERROR_MESSAGE);
                            } else {
                                String msg =
                                        cause != null
                                                ? cause.getMessage()
                                                : Res.getString("AI_INDEX_UNEXPECTED_ERROR");
                                JOptionPane.showMessageDialog(
                                        AiIndexBookmarksDialog.this,
                                        msg,
                                        Res.getString("AI_INDEX_ERROR_TITLE"),
                                        JOptionPane.ERROR_MESSAGE);
                            }
                        }
                    }
                };
        activeWorker.execute();
    }

    private void setFormEnabled(boolean enabled) {
        spinnerStartPage.setEnabled(enabled);
        spinnerEndPage.setEnabled(enabled);
        spinnerOffset.setEnabled(enabled);
        btnAnalyze.setEnabled(enabled);
    }
}
