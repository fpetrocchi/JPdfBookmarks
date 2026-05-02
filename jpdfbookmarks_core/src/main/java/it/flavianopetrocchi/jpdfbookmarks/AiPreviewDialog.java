package it.flavianopetrocchi.jpdfbookmarks;

import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiOrchestrationException;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiOrchestrator;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiModelConverter;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.CloudPaidBookmarksFetcher;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.PdfPageLabelResolver;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.ProcessIndexResult;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.SupabaseAiClient;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.StripeCatalogPrices;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Anteprima indice (parte dei segnalibri leggibili fino al pagamento), acquisto Standard tramite URL di checkout
 * fornito dal backend, verifica stato all'apertura se il task risulta già pagato, e polling ogni 5 secondi quando serve.
 */
public class AiPreviewDialog extends JDialog {

    private static final Logger LOG = Logger.getLogger(AiPreviewDialog.class.getName());

    /** Percentuale di nodi segnalibro (preorder) mostrati in chiaro nell'anteprima gratuita. */
    private static final double PREVIEW_VISIBLE_FRACTION = 0.30;

    /** Modalità del timer di polling verso check-payment. */
    private enum PollKind {
        /** Nessun polling attivo. */
        NONE,
        /** In attesa del pagamento (checkout standard). */
        WAIT_FIRST_PAYMENT
    }

    private final AiPreviewPaymentConfig paymentConfig;
    private final CloudPaidBookmarksFetcher fetcher;
    private final String taskId;
    private final boolean hasTaskId;
    private final Consumer<List<AiBookmark>> onPaidBookmarksReady;
    /** Può essere null: in tal caso la preview non naviga il PDF. */
    private final IPdfView pdfNavigationView;
    /**
     * Stesso scostamento usato da {@link it.flavianopetrocchi.jpdfbookmarks.ai.service.AiModelConverter#toAppBookmark} sui
     * numeri di pagina positivi, così anteprima e navigazione coincidono con i segnalibri dopo l'acquisto.
     */
    private final int pageNumberOffset;
    /**
     * Intervallo indice scelto dall'utente prima del clamp anteprima cloud; 0/0 = non usare riesecuzione pagata estesa.
     */
    private final PDDocument indexDocument;

    private final AiOrchestrator orchestratorForExtension;
    private final PdfPageLabelResolver pageLabelResolver;
    private final int userFullIndexStart;
    private final int userFullIndexEnd;
    private final JTextArea statusArea = new JTextArea(2, 36);
    private final JLabel lblError = new JLabel(" ");
    private final AtomicBoolean pollingActive = new AtomicBoolean(false);
    /** Etichetta prezzo Standard nella scheda non pagata; aggiornata in background se è configurato l'URL catalogo. */
    private final AtomicReference<JLabel> liveStandardPriceLabelRef = new AtomicReference<>();
    private Timer pollTimer;
    private volatile PollKind pollKind = PollKind.NONE;

    private final JTree previewTree;
    private final DefaultTreeModel previewTreeModel;
    private final JPanel dynamicPaymentArea;

    public AiPreviewDialog(
            Frame owner,
            AiPreviewPaymentConfig paymentConfig,
            String taskId,
            List<AiBookmark> previewBookmarks,
            Consumer<List<AiBookmark>> onPaidBookmarksReady,
            IPdfView pdfNavigationView,
            int pageNumberOffset) {
        this(
                owner,
                paymentConfig,
                taskId,
                previewBookmarks,
                onPaidBookmarksReady,
                pdfNavigationView,
                pageNumberOffset,
                null,
                null,
                0,
                0);
    }

    /**
     * @param indexDocument          PDF perrieseguire {@code process-index} su tutte le pagine indice dopo il pagamento
     * @param orchestratorForExtension orchestrator cloud (stesso della finestra indice)
     * @param userFullIndexStart     prima pagina indice scelta dall'utente (prima del clamp a 3 pagine)
     * @param userFullIndexEnd       ultima pagina indice scelta dall'utente
     */
    public AiPreviewDialog(
            Frame owner,
            AiPreviewPaymentConfig paymentConfig,
            String taskId,
            List<AiBookmark> previewBookmarks,
            Consumer<List<AiBookmark>> onPaidBookmarksReady,
            IPdfView pdfNavigationView,
            int pageNumberOffset,
            PDDocument indexDocument,
            AiOrchestrator orchestratorForExtension,
            int userFullIndexStart,
            int userFullIndexEnd) {
        super(owner, false);
        this.paymentConfig = Objects.requireNonNull(paymentConfig, "paymentConfig");
        this.taskId = taskId != null ? taskId.trim() : "";
        this.hasTaskId = !this.taskId.isEmpty();
        this.onPaidBookmarksReady = Objects.requireNonNull(onPaidBookmarksReady, "onPaidBookmarksReady");
        this.pdfNavigationView = pdfNavigationView;
        this.pageNumberOffset = pageNumberOffset;
        this.indexDocument = indexDocument;
        this.orchestratorForExtension = orchestratorForExtension;
        this.pageLabelResolver = PdfPageLabelResolver.create(indexDocument);
        this.userFullIndexStart = userFullIndexStart;
        this.userFullIndexEnd = userFullIndexEnd;
        this.fetcher = CloudPaidBookmarksFetcher.fromAiPreviewPaymentConfig(paymentConfig);

        setTitle(Res.getString("AI_PREVIEW_WINDOW_TITLE"));
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);

        lblError.setForeground(new Color(180, 0, 0));
        Font ef = lblError.getFont();
        lblError.setFont(ef.deriveFont(Font.PLAIN, Math.max(11f, ef.getSize2D() - 0.5f)));
        if (!hasTaskId) {
            lblError.setText(Res.getString("AI_PREVIEW_MISSING_TASK_ID"));
        }

        DefaultMutableTreeNode root =
                buildPreviewTree(previewBookmarks, pageNumberOffset, PREVIEW_VISIBLE_FRACTION, pageLabelResolver);
        previewTreeModel = new DefaultTreeModel(root);
        previewTree = new JTree(previewTreeModel);
        previewTree.setRootVisible(true);
        previewTree.setShowsRootHandles(true);
        previewTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        previewTree.setRowHeight(22);
        TreeSelectionListener navListener = e -> onPreviewTreeSelection(e.getNewLeadSelectionPath());
        previewTree.addTreeSelectionListener(navListener);
        JScrollPane treeScroll = new JScrollPane(previewTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder(Res.getString("AI_PREVIEW_INDEX_PANEL_TITLE")));

        JPanel left = new JPanel(new BorderLayout());
        left.add(treeScroll, BorderLayout.CENTER);

        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setAlignmentX(Component.LEFT_ALIGNMENT);
        right.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        dynamicPaymentArea = new JPanel();
        dynamicPaymentArea.setLayout(new BoxLayout(dynamicPaymentArea, BoxLayout.Y_AXIS));
        dynamicPaymentArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (hasTaskId && !paymentConfig.cloudCheckPaymentUrl().isBlank()) {
            dynamicPaymentArea.add(new JLabel(htmlWrap(Res.getString("AI_PREVIEW_CHECKING_SERVER"), 420)));
        } else {
            populateUnpaidPaymentLayout();
        }
        right.add(dynamicPaymentArea);
        right.add(Box.createVerticalStrut(10));
        right.add(lblError);
        lblError.setAlignmentX(Component.LEFT_ALIGNMENT);

        JScrollPane rightScroll = new JScrollPane(right);
        rightScroll.setBorder(null);
        rightScroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightScroll);
        split.setResizeWeight(0.45);
        split.setDividerLocation(0.45);

        JPanel south = new JPanel(new BorderLayout(8, 6));
        south.setBorder(BorderFactory.createEmptyBorder(4, 12, 10, 12));
        south.add(new JScrollPane(statusArea), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton btnClose = new JButton(Res.getString("AI_PREVIEW_CLOSE"));
        btnClose.addActionListener(
                e -> {
                    closeAndStop();
                    dispose();
                });
        buttons.add(btnClose);
        south.add(buttons, BorderLayout.SOUTH);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);

        addWindowListener(
                new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        closeAndStop();
                    }
                });

        setPreferredSize(new Dimension(1000, 600));
        pack();
        setLocationRelativeTo(owner);

        if (hasTaskId && !paymentConfig.cloudCheckPaymentUrl().isBlank()) {
            statusArea.setText(Res.getString("AI_PREVIEW_CHECKING_SERVER_STATUS"));
            runInitialServerCheck();
        } else if (hasTaskId) {
            statusArea.setText(Res.getString("AI_PREVIEW_POLLING_DISABLED"));
        } else {
            statusArea.setText(Res.getString("AI_PREVIEW_STATUS_NO_TASK"));
        }

        if (!hasTaskId || paymentConfig.cloudCheckPaymentUrl().isBlank()) {
            configurePollingIfUnpaid();
        }
    }

    private void onPreviewTreeSelection(TreePath path) {
        if (path == null || pdfNavigationView == null) {
            return;
        }
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode node)) {
            return;
        }
        Object uo = node.getUserObject();
        if (!(uo instanceof PreviewNode pn)) {
            return;
        }
        if (!pn.navigable || pn.pageNumber == null) {
            return;
        }
        if (pdfNavigationView instanceof Component c && !c.isDisplayable()) {
            return;
        }
        int p = pn.pageNumber;
        int max = pdfNavigationView.getNumPages();
        if (max > 0 && p >= 1 && p <= max) {
            pdfNavigationView.goToPage(p);
        }
    }

    private void runInitialServerCheck() {
        new SwingWorker<SupabaseAiClient.CheckPaymentResult, Void>() {
            @Override
            protected SupabaseAiClient.CheckPaymentResult doInBackground() throws Exception {
                return fetcher.checkPayment(taskId);
            }

            @Override
            protected void done() {
                if (!isDisplayable()) {
                    return;
                }
                try {
                    applyInitialCheckResult(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    String msg =
                            c instanceof AiOrchestrationException
                                    ? c.getMessage()
                                    : (c.getMessage() != null ? c.getMessage() : ex.toString());
                    statusArea.setText(msg);
                    dynamicPaymentArea.removeAll();
                    liveStandardPriceLabelRef.set(null);
                    populateUnpaidPaymentLayout();
                    dynamicPaymentArea.revalidate();
                    dynamicPaymentArea.repaint();
                    configurePollingIfUnpaid();
                }
            }
        }.execute();
    }

    private void applyInitialCheckResult(SupabaseAiClient.CheckPaymentResult r) {
        dynamicPaymentArea.removeAll();
        liveStandardPriceLabelRef.set(null);
        if (r.paid() && !r.bookmarksWhenPaid().isEmpty()) {
            statusArea.setText(Res.getString("AI_PREVIEW_ALREADY_PAID_STATUS"));
            showPaidFullDownloadPanel(r.bookmarksWhenPaid());
        } else if (r.paid()) {
            statusArea.setText(Res.getString("AI_PREVIEW_ALREADY_PAID_STATUS"));
            showPaidEmptyBookmarksPanel();
        } else {
            statusArea.setText(Res.getString("AI_PREVIEW_POLLING"));
            populateUnpaidPaymentLayout();
            configurePollingIfUnpaid();
        }
        dynamicPaymentArea.revalidate();
        dynamicPaymentArea.repaint();
    }

    private void populateUnpaidPaymentLayout() {
        liveStandardPriceLabelRef.set(null);
        dynamicPaymentArea.add(buildPaymentCard("standard"));
        scheduleStripePriceRefreshIfConfigured();
    }

    private void scheduleStripePriceRefreshIfConfigured() {
        if (paymentConfig.cloudStripePricesUrl() == null || paymentConfig.cloudStripePricesUrl().isBlank()) {
            return;
        }
        final JLabel target = liveStandardPriceLabelRef.get();
        if (target == null) {
            return;
        }
        new SwingWorker<Optional<StripeCatalogPrices.Result>, Void>() {
            @Override
            protected Optional<StripeCatalogPrices.Result> doInBackground() {
                return fetcher.tryFetchStripeCatalogPrices();
            }

            @Override
            protected void done() {
                if (!isDisplayable()) {
                    return;
                }
                JLabel lab = liveStandardPriceLabelRef.get();
                if (lab == null || lab != target) {
                    return;
                }
                try {
                    Optional<StripeCatalogPrices.Result> opt = get();
                    opt.flatMap(r -> Optional.ofNullable(r.standard()))
                            .ifPresent(
                                    tier -> {
                                        String s = StripeCatalogPrices.format(tier);
                                        if (!s.isBlank()) {
                                            lab.setText(s);
                                        }
                                    });
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    private void showPaidFullDownloadPanel(List<AiBookmark> bookmarks) {
        if (needsFullPaidReextract()) {
            replaceTreeWithFullBookmarks(bookmarks);
            statusArea.setText(Res.getString("AI_PREVIEW_REEXTRACT_FULL_INDEX_STATUS"));
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            new SwingWorker<ProcessIndexResult, Void>() {
                @Override
                protected ProcessIndexResult doInBackground() throws Exception {
                    return orchestratorForExtension.processIndex(
                            indexDocument, userFullIndexStart, userFullIndexEnd, taskId);
                }

                @Override
                protected void done() {
                    setCursor(Cursor.getDefaultCursor());
                    if (!isDisplayable()) {
                        return;
                    }
                    try {
                        ProcessIndexResult r = get();
                        List<AiBookmark> bm = r.getBookmarks();
                        if (bm != null && !bm.isEmpty()) {
                            paidPanelAfterBookmarksResolved(bm);
                        } else {
                            JOptionPane.showMessageDialog(
                                    AiPreviewDialog.this,
                                    Res.getString("AI_PREVIEW_REEXTRACT_FAILED"),
                                    Res.getString("AI_PREVIEW_WINDOW_TITLE"),
                                    JOptionPane.WARNING_MESSAGE);
                            paidPanelAfterBookmarksResolved(bookmarks);
                        }
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        String msg =
                                c instanceof AiOrchestrationException
                                        ? c.getMessage()
                                        : (c.getMessage() != null ? c.getMessage() : ex.toString());
                        JOptionPane.showMessageDialog(
                                AiPreviewDialog.this,
                                msg + "\n\n" + Res.getString("AI_PREVIEW_REEXTRACT_FAILED"),
                                Res.getString("AI_INDEX_ERROR_TITLE"),
                                JOptionPane.ERROR_MESSAGE);
                        paidPanelAfterBookmarksResolved(bookmarks);
                    }
                }
            }.execute();
            return;
        }
        paidPanelAfterBookmarksResolved(bookmarks);
    }

    private void paidPanelAfterBookmarksResolved(List<AiBookmark> bookmarks) {
        replaceTreeWithFullBookmarks(bookmarks);
        dynamicPaymentArea.add(new JLabel(htmlWrap(Res.getString("AI_PREVIEW_ALREADY_PAID_DOWNLOAD_HINT"), 420)));
        dynamicPaymentArea.add(Box.createVerticalStrut(10));
        JButton download = new JButton(Res.getString("AI_PREVIEW_BTN_DOWNLOAD_PURCHASED"));
        styleGreenButton(download);
        download.addActionListener(e -> finishWithBookmarksAndClose(bookmarks));
        alignFullWidth(download);
        dynamicPaymentArea.add(download);
    }

    private void showPaidEmptyBookmarksPanel() {
        dynamicPaymentArea.add(new JLabel(htmlWrap(Res.getString("AI_PREVIEW_PAID_EMPTY_BOOKMARKS_HINT"), 420)));
        dynamicPaymentArea.add(Box.createVerticalStrut(10));
        JButton tryFetch = new JButton(Res.getString("AI_PREVIEW_BTN_DOWNLOAD_PURCHASED"));
        styleGreenButton(tryFetch);
        boolean canTry = !paymentConfig.cloudFetchFullResultsUrl().isBlank();
        tryFetch.setEnabled(canTry);
        if (!canTry) {
            tryFetch.setToolTipText(Res.getString("AI_PREVIEW_FETCH_URL_REQUIRED_TOOLTIP"));
        }
        tryFetch.addActionListener(e -> fetchFullAndFinish());
        alignFullWidth(tryFetch);
        dynamicPaymentArea.add(tryFetch);
    }

    private static void alignFullWidth(JButton b) {
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension pref = b.getPreferredSize();
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
    }

    private void replaceTreeWithFullBookmarks(List<AiBookmark> roots) {
        DefaultMutableTreeNode top =
                new DefaultMutableTreeNode(new PreviewNode(Res.getString("AI_PREVIEW_TREE_ROOT"), null, false));
        if (roots != null) {
            for (AiBookmark b : roots) {
                addFullBookmarkNodes(top, b);
            }
        }
        previewTreeModel.setRoot(top);
    }

    private void addFullBookmarkNodes(DefaultMutableTreeNode parent, AiBookmark b) {
        Integer p = bookmarkPageOrNull(b, pageNumberOffset, pageLabelResolver);
        DefaultMutableTreeNode n =
                new DefaultMutableTreeNode(
                        new PreviewNode(formatVisibleBookmark(b, pageNumberOffset, pageLabelResolver), p, true));
        parent.add(n);
        for (AiBookmark c : b.getChildrenView()) {
            addFullBookmarkNodes(n, c);
        }
    }

    /**
     * @param checkoutTier {@code standard} o {@code advanced}, inviato al backend create-checkout.
     */
    private JPanel buildPaymentCard(String checkoutTier) {
        JPanel card = new JPanel(new GridBagLayout());
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xc0, 0xc0, 0xc0), 1),
                BorderFactory.createEmptyBorder(12, 14, 14, 14)));
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridwidth = GridBagConstraints.REMAINDER;
        c.insets = new Insets(2, 0, 8, 0);
        c.gridx = 0;
        c.gridy = 0;
        JLabel h = new JLabel(htmlCardHeading(Res.getString("AI_PREVIEW_CARD_STANDARD_TITLE")));
        h.setHorizontalAlignment(SwingConstants.LEFT);
        Font hf = h.getFont();
        h.setFont(hf.deriveFont(Font.BOLD, hf.getSize2D() + 2.5f));
        card.add(h, c);
        c.gridy = 1;
        JLabel bodyLab = new JLabel(htmlWrap(Res.getString("AI_PREVIEW_CARD_STANDARD_BODY"), 420));
        bodyLab.setHorizontalAlignment(SwingConstants.LEFT);
        card.add(bodyLab, c);
        c.gridy = 2;
        JLabel priceLab = new JLabel(Res.getString("AI_PREVIEW_STANDARD_PRICE"));
        priceLab.setHorizontalAlignment(SwingConstants.LEFT);
        Font pf = priceLab.getFont();
        priceLab.setFont(pf.deriveFont(Font.BOLD, pf.getSize2D() + 4f));
        card.add(priceLab, c);
        liveStandardPriceLabelRef.set(priceLab);
        c.gridy = 3;
        c.insets = new Insets(12, 0, 0, 0);
        JButton pay = new JButton(Res.getString("AI_PREVIEW_BTN_STANDARD"));
        styleGreenButton(pay);
        String endpoint =
                paymentConfig.cloudCreateCheckoutUrl() != null ? paymentConfig.cloudCreateCheckoutUrl().trim() : "";
        boolean canPay = hasTaskId && !endpoint.isEmpty();
        pay.setEnabled(canPay);
        if (!hasTaskId) {
            pay.setToolTipText(Res.getString("AI_PREVIEW_WAIT_TASK_TOOLTIP"));
        } else if (endpoint.isEmpty()) {
            pay.setToolTipText(Res.getString("AI_PREVIEW_CHECKOUT_URL_MISSING_TOOLTIP"));
        }
        pay.addActionListener(e -> startCreateCheckoutAndBrowse(pay, checkoutTier));
        card.add(pay, c);
        return card;
    }

    private void startCreateCheckoutAndBrowse(JButton payButton, String checkoutTier) {
        String configuredCheckoutUrl =
                paymentConfig.cloudCreateCheckoutUrl() != null ? paymentConfig.cloudCreateCheckoutUrl().trim() : "";
        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(
                    Level.INFO,
                    "create-checkout button: tier={0} taskId={1} configuredUrl={2}",
                    new Object[] {checkoutTier, taskId, configuredCheckoutUrl});
        }
        payButton.setEnabled(false);
        payButton.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return fetcher.createCheckout(taskId, checkoutTier);
            }

            @Override
            protected void done() {
                payButton.setEnabled(true);
                payButton.setCursor(Cursor.getDefaultCursor());
                if (!isDisplayable()) {
                    return;
                }
                try {
                    String checkoutUrl = get();
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI.create(checkoutUrl.trim()));
                    } else {
                        JOptionPane.showMessageDialog(
                                AiPreviewDialog.this,
                                checkoutUrl,
                                Res.getString("AI_PREVIEW_WINDOW_TITLE"),
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    String msg =
                            c instanceof AiOrchestrationException
                                    ? c.getMessage()
                                    : (c.getMessage() != null ? c.getMessage() : ex.toString());
                    LOG.log(Level.WARNING, "create-checkout failed (tier=" + checkoutTier + ", taskId=" + taskId + "): " + msg, ex);
                    JOptionPane.showMessageDialog(
                            AiPreviewDialog.this,
                            msg,
                            Res.getString("AI_INDEX_ERROR_TITLE"),
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static void styleGreenButton(JButton b) {
        Color green = new Color(46, 125, 50);
        Color greenEdge = new Color(35, 95, 40);
        /*
         * Windows (e altri LaF nativi) spesso ignorano setBackground su JButton lasciando solo il testo “disabled”
         * grigio, soprattutto senza FlatLaf (es. bundle .zip in Sandbox). BasicButtonUI rispetta sfondo/bordo.
         */
        if (!isFlatLookAndFeel()) {
            b.setUI(new BasicButtonUI());
        }
        b.setBackground(green);
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setFocusPainted(false);
        b.setBorderPainted(true);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(greenEdge, 1),
                BorderFactory.createEmptyBorder(8, 16, 8, 16)));
        b.setFont(b.getFont().deriveFont(Font.BOLD, b.getFont().getSize2D() + 1f));
    }

    private static boolean isFlatLookAndFeel() {
        try {
            return UIManager.getLookAndFeel().getClass().getName().toLowerCase(Locale.ROOT).contains("flat");
        } catch (Exception e) {
            return false;
        }
    }

    private void closeAndStop() {
        pollingActive.set(false);
        pollKind = PollKind.NONE;
        if (pollTimer != null) {
            pollTimer.stop();
            pollTimer = null;
        }
    }

    private void configurePollingIfUnpaid() {
        if (!hasTaskId || paymentConfig.cloudCheckPaymentUrl().isBlank()) {
            return;
        }
        startPolling(PollKind.WAIT_FIRST_PAYMENT);
    }

    private void startPolling(PollKind kind) {
        if (!hasTaskId || paymentConfig.cloudCheckPaymentUrl().isBlank()) {
            return;
        }
        pollKind = kind;
        pollingActive.set(true);
        if (pollTimer != null) {
            pollTimer.stop();
        }
        pollTimer =
                new Timer(
                        5000,
                        e -> {
                            if (!pollingActive.get() || !isDisplayable()) {
                                return;
                            }
                            pollPaymentOnce();
                        });
        pollTimer.setInitialDelay(5000);
        pollTimer.start();
    }

    private void pollPaymentOnce() {
        final PollKind kindAtStart = pollKind;
        new SwingWorker<SupabaseAiClient.CheckPaymentResult, Void>() {
            @Override
            protected SupabaseAiClient.CheckPaymentResult doInBackground() throws Exception {
                return fetcher.checkPayment(taskId);
            }

            @Override
            protected void done() {
                if (!pollingActive.get() || !isDisplayable()) {
                    return;
                }
                try {
                    SupabaseAiClient.CheckPaymentResult r = get();
                    if (kindAtStart != PollKind.WAIT_FIRST_PAYMENT) {
                        return;
                    }
                    if (!r.paid()) {
                        return;
                    }
                    if (!r.bookmarksWhenPaid().isEmpty()) {
                        closeAndStop();
                        statusArea.setText(Res.getString("AI_PREVIEW_PAYMENT_DETECTED"));
                        finishWithBookmarks(r.bookmarksWhenPaid());
                        return;
                    }
                    if (!paymentConfig.cloudFetchFullResultsUrl().isBlank()) {
                        closeAndStop();
                        statusArea.setText(Res.getString("AI_PREVIEW_PAYMENT_DETECTED"));
                        fetchFullAndFinish();
                    } else {
                        closeAndStop();
                        statusArea.setText(Res.getString("AI_PREVIEW_PAID_NO_BOOKMARKS_NO_FETCH"));
                    }
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    String msg =
                            c instanceof AiOrchestrationException
                                    ? c.getMessage()
                                    : (c.getMessage() != null ? c.getMessage() : ex.toString());
                    statusArea.setText(msg);
                }
            }
        }.execute();
    }

    private boolean needsFullPaidReextract() {
        if (orchestratorForExtension == null
                || indexDocument == null
                || !orchestratorForExtension.usesCloudService()) {
            return false;
        }
        if (!hasTaskId) {
            return false;
        }
        if (userFullIndexStart < 1 || userFullIndexEnd < userFullIndexStart) {
            return false;
        }
        int span = userFullIndexEnd - userFullIndexStart + 1;
        return span > Prefs.CLOUD_FREE_PREVIEW_MAX_INDEX_PAGES;
    }

    private void fetchFullAndFinish() {
        new SwingWorker<List<AiBookmark>, Void>() {
            @Override
            protected List<AiBookmark> doInBackground() throws Exception {
                return fetcher.fetchFullBookmarks(taskId);
            }

            @Override
            protected void done() {
                try {
                    finishWithBookmarks(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    String msg =
                            c instanceof AiOrchestrationException
                                    ? c.getMessage()
                                    : (c.getMessage() != null ? c.getMessage() : ex.toString());
                    JOptionPane.showMessageDialog(
                            AiPreviewDialog.this,
                            msg,
                            Res.getString("AI_INDEX_ERROR_TITLE"),
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void finishWithBookmarks(List<AiBookmark> list) {
        if (list == null || list.isEmpty()) {
            JOptionPane.showMessageDialog(
                    AiPreviewDialog.this,
                    Res.getString("AI_PREVIEW_FETCH_EMPTY"),
                    Res.getString("AI_PREVIEW_WINDOW_TITLE"),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (needsFullPaidReextract()) {
            statusArea.setText(Res.getString("AI_PREVIEW_REEXTRACT_FULL_INDEX_STATUS"));
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            new SwingWorker<ProcessIndexResult, Void>() {
                @Override
                protected ProcessIndexResult doInBackground() throws Exception {
                    return orchestratorForExtension.processIndex(
                            indexDocument, userFullIndexStart, userFullIndexEnd, taskId);
                }

                @Override
                protected void done() {
                    setCursor(Cursor.getDefaultCursor());
                    if (!isDisplayable()) {
                        return;
                    }
                    try {
                        ProcessIndexResult r = get();
                        List<AiBookmark> bm = r.getBookmarks();
                        if (bm != null && !bm.isEmpty()) {
                            finishWithBookmarksAndClose(bm);
                        } else {
                            JOptionPane.showMessageDialog(
                                    AiPreviewDialog.this,
                                    Res.getString("AI_PREVIEW_REEXTRACT_FAILED"),
                                    Res.getString("AI_PREVIEW_WINDOW_TITLE"),
                                    JOptionPane.WARNING_MESSAGE);
                            finishWithBookmarksAndClose(list);
                        }
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        String msg =
                                c instanceof AiOrchestrationException
                                        ? c.getMessage()
                                        : (c.getMessage() != null ? c.getMessage() : ex.toString());
                        JOptionPane.showMessageDialog(
                                AiPreviewDialog.this,
                                msg + "\n\n" + Res.getString("AI_PREVIEW_REEXTRACT_FAILED"),
                                Res.getString("AI_INDEX_ERROR_TITLE"),
                                JOptionPane.ERROR_MESSAGE);
                        finishWithBookmarksAndClose(list);
                    }
                }
            }.execute();
            return;
        }
        finishWithBookmarksAndClose(list);
    }

    private void finishWithBookmarksAndClose(List<AiBookmark> list) {
        closeAndStop();
        dispose();
        onPaidBookmarksReady.accept(list);
    }

    private static DefaultMutableTreeNode buildPreviewTree(
            List<AiBookmark> roots,
            int pageNumberOffset,
            double preorderVisibleFraction,
            PdfPageLabelResolver pageLabelResolver) {
        int total = countPreorderBookmarks(roots);
        int visibleLimit = previewVisibleLimit(total, preorderVisibleFraction);
        DefaultMutableTreeNode top =
                new DefaultMutableTreeNode(new PreviewNode(Res.getString("AI_PREVIEW_TREE_ROOT"), null, false));
        int[] preorder = {0};
        if (roots != null) {
            for (AiBookmark b : roots) {
                addPreorder(top, b, preorder, visibleLimit, pageNumberOffset, pageLabelResolver);
            }
        }
        return top;
    }

    private static void addPreorder(
            DefaultMutableTreeNode parent,
            AiBookmark b,
            int[] preorder,
            int visibleLimit,
            int pageNumberOffset,
            PdfPageLabelResolver pageLabelResolver) {
        preorder[0]++;
        int idx = preorder[0];
        boolean unlocked = idx <= visibleLimit;
        String display =
                unlocked
                        ? formatVisibleBookmark(b, pageNumberOffset, pageLabelResolver)
                        : Res.getString("AI_PREVIEW_LOCKED_NODE");
        Integer page = unlocked ? bookmarkPageOrNull(b, pageNumberOffset, pageLabelResolver) : null;
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(new PreviewNode(display, page, unlocked));
        parent.add(n);
        for (AiBookmark c : b.getChildrenView()) {
            addPreorder(n, c, preorder, visibleLimit, pageNumberOffset, pageLabelResolver);
        }
    }

    private static int countPreorderBookmarks(List<AiBookmark> roots) {
        int[] c = {0};
        if (roots == null) {
            return 0;
        }
        for (AiBookmark b : roots) {
            countPreorderRecursive(b, c);
        }
        return c[0];
    }

    private static void countPreorderRecursive(AiBookmark b, int[] c) {
        c[0]++;
        for (AiBookmark ch : b.getChildrenView()) {
            countPreorderRecursive(ch, c);
        }
    }

    private static int previewVisibleLimit(int totalBookmarkNodes, double fraction) {
        if (totalBookmarkNodes <= 0) {
            return 0;
        }
        double f = fraction > 0 && fraction <= 1.0 ? fraction : PREVIEW_VISIBLE_FRACTION;
        int v = (int) Math.ceil(totalBookmarkNodes * f);
        return Math.min(totalBookmarkNodes, Math.max(1, v));
    }

    /**
     * Pagina effettiva per anteprima/navigazione: come {@link it.flavianopetrocchi.jpdfbookmarks.ai.service.AiModelConverter},
     * somma {@code pageNumberOffset} solo se il numero restituito dall'IA è {@code >= 1}.
     */
    private static Integer bookmarkPageOrNull(
            AiBookmark b, int pageNumberOffset, PdfPageLabelResolver pageLabelResolver) {
        return AiModelConverter.resolveTargetPageNumber(b, pageNumberOffset, pageLabelResolver);
    }

    private static String formatVisibleBookmark(
            AiBookmark b, int pageNumberOffset, PdfPageLabelResolver pageLabelResolver) {
        String t = b.getTitle() != null ? b.getTitle() : "";
        PdfPageLabelResolver resolver =
                pageLabelResolver != null ? pageLabelResolver : PdfPageLabelResolver.unavailable();
        PdfPageLabelResolver.Resolution r = resolver.resolve(b, pageNumberOffset);
        String display = r.displayLabel();
        if (display != null && !display.isBlank()) {
            return t + " [" + display + "]";
        }
        return t;
    }

    private static String htmlWrap(String s, int maxWidthPx) {
        return "<html><body style='width: "
                + maxWidthPx
                + "px; text-align:left'>"
                + s
                + "</body></html>";
    }

    private static String htmlCardHeading(String title) {
        return "<html><body style='width:420px;text-align:left'><b>"
                + (title != null ? title : "")
                + "</b></body></html>";
    }

    private static final class PreviewNode {
        private final String displayText;
        private final Integer pageNumber;
        private final boolean navigable;

        private PreviewNode(String displayText, Integer pageNumber, boolean navigable) {
            this.displayText = displayText;
            this.pageNumber = pageNumber;
            this.navigable = navigable;
        }

        @Override
        public String toString() {
            return displayText;
        }
    }
}
