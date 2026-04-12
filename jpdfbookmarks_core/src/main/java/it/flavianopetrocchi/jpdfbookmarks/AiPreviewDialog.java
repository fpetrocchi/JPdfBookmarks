package it.flavianopetrocchi.jpdfbookmarks;

import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiOrchestrationException;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.CloudPaidBookmarksFetcher;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.SupabaseAiClient;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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
import javax.swing.Timer;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

/**
 * Anteprima indice (primi segnalibri leggibili, resto offuscato), acquisto Standard/Advanced via Stripe e polling
 * pagamento ogni 5 secondi fino al download completo.
 */
public class AiPreviewDialog extends JDialog {

    private static final int PREORDER_VISIBLE_BOOKMARKS = 3;

    private final AiPreviewPaymentConfig paymentConfig;
    private final CloudPaidBookmarksFetcher fetcher;
    private final String taskId;
    private final boolean hasTaskId;
    private final Consumer<List<AiBookmark>> onPaidBookmarksReady;
    private final JTextArea statusArea = new JTextArea(2, 36);
    private final JLabel lblError = new JLabel(" ");
    private final AtomicBoolean pollingActive = new AtomicBoolean(true);
    private Timer pollTimer;

    /**
     * Aggiunge {@code client_reference_id} e {@code taskId} alla URL di checkout Stripe.
     */
    public static String appendStripeCheckoutQueryParams(String baseUrl, String taskId) {
        if (baseUrl == null || baseUrl.isBlank() || taskId == null || taskId.isBlank()) {
            return baseUrl != null ? baseUrl : "";
        }
        String enc = URLEncoder.encode(taskId.trim(), StandardCharsets.UTF_8);
        String q = "client_reference_id=" + enc + "&taskId=" + enc;
        if (baseUrl.contains("?")) {
            return baseUrl + "&" + q;
        }
        return baseUrl + "?" + q;
    }

    public AiPreviewDialog(
            Frame owner,
            AiPreviewPaymentConfig paymentConfig,
            String taskId,
            List<AiBookmark> previewBookmarks,
            Consumer<List<AiBookmark>> onPaidBookmarksReady) {
        super(owner, false);
        this.paymentConfig = Objects.requireNonNull(paymentConfig, "paymentConfig");
        this.taskId = taskId != null ? taskId.trim() : "";
        this.hasTaskId = !this.taskId.isEmpty();
        this.onPaidBookmarksReady = Objects.requireNonNull(onPaidBookmarksReady, "onPaidBookmarksReady");
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

        DefaultMutableTreeNode root = buildPreviewTree(previewBookmarks);
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setRowHeight(22);
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createTitledBorder(Res.getString("AI_PREVIEW_INDEX_PANEL_TITLE")));

        JPanel left = new JPanel(new BorderLayout());
        left.add(treeScroll, BorderLayout.CENTER);

        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setAlignmentX(Component.LEFT_ALIGNMENT);
        right.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JLabel intro = new JLabel(htmlWrap(Res.getString("AI_PREVIEW_RIGHT_INTRO"), 440));
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);
        right.add(intro);
        right.add(Box.createVerticalStrut(14));

        right.add(buildPaymentCard(paymentConfig.cloudStripeCheckoutMiniUrl(), false));
        right.add(Box.createVerticalStrut(12));
        right.add(buildPaymentCard(paymentConfig.cloudStripeCheckoutAdvancedUrl(), true));
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

        boolean canPoll = hasTaskId && !paymentConfig.cloudCheckPaymentUrl().isBlank();
        if (canPoll) {
            statusArea.setText(Res.getString("AI_PREVIEW_POLLING"));
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
        } else if (hasTaskId) {
            statusArea.setText(Res.getString("AI_PREVIEW_POLLING_DISABLED"));
        } else {
            statusArea.setText(Res.getString("AI_PREVIEW_STATUS_NO_TASK"));
        }

        setPreferredSize(new Dimension(1000, 600));
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel buildPaymentCard(String stripeBaseUrl, boolean advanced) {
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
        String title =
                advanced
                        ? Res.getString("AI_PREVIEW_CARD_ADVANCED_TITLE")
                        : Res.getString("AI_PREVIEW_CARD_STANDARD_TITLE");
        JLabel h = new JLabel(htmlCardHeading(title));
        h.setHorizontalAlignment(SwingConstants.LEFT);
        Font hf = h.getFont();
        h.setFont(hf.deriveFont(Font.BOLD, hf.getSize2D() + 2.5f));
        card.add(h, c);
        c.gridy = 1;
        String body =
                advanced
                        ? Res.getString("AI_PREVIEW_CARD_ADVANCED_BODY")
                        : Res.getString("AI_PREVIEW_CARD_STANDARD_BODY");
        JLabel bodyLab = new JLabel(htmlWrap(body, 420));
        bodyLab.setHorizontalAlignment(SwingConstants.LEFT);
        card.add(bodyLab, c);
        c.gridy = 2;
        String price =
                advanced
                        ? Res.getString("AI_PREVIEW_ADVANCED_PRICE")
                        : Res.getString("AI_PREVIEW_STANDARD_PRICE");
        JLabel priceLab = new JLabel(price);
        priceLab.setHorizontalAlignment(SwingConstants.LEFT);
        Font pf = priceLab.getFont();
        priceLab.setFont(pf.deriveFont(Font.BOLD, pf.getSize2D() + 4f));
        card.add(priceLab, c);
        c.gridy = 3;
        c.insets = new Insets(12, 0, 0, 0);
        JButton pay =
                new JButton(
                        advanced
                                ? Res.getString("AI_PREVIEW_BTN_ADVANCED")
                                : Res.getString("AI_PREVIEW_BTN_STANDARD"));
        styleGreenButton(pay);
        String url = stripeBaseUrl != null ? stripeBaseUrl.trim() : "";
        boolean canPay = hasTaskId && !url.isEmpty();
        pay.setEnabled(canPay);
        if (!hasTaskId) {
            pay.setToolTipText(Res.getString("AI_PREVIEW_WAIT_TASK_TOOLTIP"));
        } else if (url.isEmpty()) {
            pay.setToolTipText(Res.getString("AI_PREVIEW_STRIPE_URL_MISSING_TOOLTIP"));
        }
        pay.addActionListener(
                e -> {
                    try {
                        String withParams = appendStripeCheckoutQueryParams(url, taskId);
                        if (Desktop.isDesktopSupported()
                                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            Desktop.getDesktop().browse(URI.create(withParams));
                        } else {
                            JOptionPane.showMessageDialog(
                                    AiPreviewDialog.this,
                                    withParams,
                                    Res.getString("AI_PREVIEW_WINDOW_TITLE"),
                                    JOptionPane.INFORMATION_MESSAGE);
                        }
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(
                                AiPreviewDialog.this,
                                ex.getMessage(),
                                Res.getString("AI_INDEX_ERROR_TITLE"),
                                JOptionPane.ERROR_MESSAGE);
                    }
                });
        card.add(pay, c);
        return card;
    }

    private static void styleGreenButton(JButton b) {
        Color green = new Color(46, 125, 50);
        b.setBackground(green);
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setFont(b.getFont().deriveFont(Font.BOLD, b.getFont().getSize2D() + 1f));
    }

    private void closeAndStop() {
        pollingActive.set(false);
        if (pollTimer != null) {
            pollTimer.stop();
            pollTimer = null;
        }
    }

    private void pollPaymentOnce() {
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
                    if (!r.paid()) {
                        return;
                    }
                    if (!r.bookmarksWhenPaid().isEmpty()) {
                        closeAndStop();
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
        closeAndStop();
        dispose();
        onPaidBookmarksReady.accept(list);
    }

    private static DefaultMutableTreeNode buildPreviewTree(List<AiBookmark> roots) {
        DefaultMutableTreeNode top =
                new DefaultMutableTreeNode(Res.getString("AI_PREVIEW_TREE_ROOT"));
        int[] preorder = {0};
        if (roots != null) {
            for (AiBookmark b : roots) {
                addPreorder(top, b, preorder);
            }
        }
        return top;
    }

    private static void addPreorder(DefaultMutableTreeNode parent, AiBookmark b, int[] preorder) {
        preorder[0]++;
        String label =
                preorder[0] <= PREORDER_VISIBLE_BOOKMARKS ? formatVisibleBookmark(b) : "********";
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(label);
        parent.add(n);
        for (AiBookmark c : b.getChildrenView()) {
            addPreorder(n, c, preorder);
        }
    }

    private static String formatVisibleBookmark(AiBookmark b) {
        String t = b.getTitle() != null ? b.getTitle() : "";
        Integer p = b.getPageNumber();
        if (p != null) {
            return t + " [" + p + "]";
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

    /** Titolo card: larghezza fissa e allineamento a sinistra (JLabel+HTML altrimenti tende al centro). */
    private static String htmlCardHeading(String title) {
        return "<html><body style='width:420px;text-align:left'><b>"
                + (title != null ? title : "")
                + "</b></body></html>";
    }
}
