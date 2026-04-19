package it.flavianopetrocchi.jpdfbookmarks;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import it.flavianopetrocchi.jpdfbookmarks.ai.service.SupabaseAiClient;

/**
 * Pannello opzioni IA: estrazione indice in locale (OpenAI; Ollama nel codice ma non in combo) oppure tramite cloud
 * (Supabase Edge o compatibile).
 */
public class AiOptionsPanel extends JPanel {

    private static final String PROVIDER_OPENAI = "OPENAI";
    @SuppressWarnings("unused")
    private static final String PROVIDER_OLLAMA = "OLLAMA";

    private static final String[] OPENAI_MODEL_PRESETS = {Prefs.ALLOWED_OPENAI_MODEL_FOR_EXTRACTION};

    private final JRadioButton radioLocal = new JRadioButton(Res.getString("AI_OPTIONS_MODE_LOCAL"));
    private final JRadioButton radioCloud = new JRadioButton(Res.getString("AI_OPTIONS_MODE_CLOUD"));
    private final JPasswordField fieldApiKey = new JPasswordField(32);
    private final JComboBox<String> comboOpenAiModel = new JComboBox<>(OPENAI_MODEL_PRESETS);
    private final JTextField fieldOllamaUrl = new JTextField(32);
    private final JTextField fieldOllamaModel = new JTextField(32);

    private final JTextField fieldCloudProcessUrl = new JTextField(32);
    private final JPasswordField fieldCloudAnonKey = new JPasswordField(32);
    private final JTextField fieldCloudCheckUrl = new JTextField(32);
    private final JTextField fieldCloudFetchFullUrl = new JTextField(32);
    private final JTextField fieldCloudStripeMiniUrl = new JTextField(32);
    private final JTextField fieldCloudStripePricesUrl = new JTextField(32);

    private final JPanel panelOpenAi;
    private final JPanel panelOllama;
    private final JPanel panelCloud;

    public AiOptionsPanel() {
        super(new GridBagLayout());

        panelOpenAi = buildOpenAiSection();
        panelOllama = buildOllamaSection();
        panelCloud = buildCloudSection();

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(radioLocal);
        modeGroup.add(radioCloud);

        ActionListener modeListener = e -> updateModeVisibility();
        radioLocal.addActionListener(modeListener);
        radioCloud.addActionListener(modeListener);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.LINE_START;

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        JPanel modeRow = new JPanel(new GridBagLayout());
        modeRow.setBorder(BorderFactory.createTitledBorder(Res.getString("AI_OPTIONS_EXTRACTION_MODE")));
        GridBagConstraints m = new GridBagConstraints();
        m.insets = new Insets(2, 8, 2, 12);
        m.anchor = GridBagConstraints.LINE_START;
        m.gridx = 0;
        m.gridy = 0;
        modeRow.add(radioLocal, m);
        m.gridx = 1;
        modeRow.add(radioCloud, m);
        add(modeRow, c);

        c.gridy = 1;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        add(panelOpenAi, c);

        c.gridy = 2;
        add(panelOllama, c);

        c.gridy = 3;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        add(panelCloud, c);

        comboOpenAiModel.setEditable(false);
        comboOpenAiModel.setToolTipText(Res.getString("AI_OPTIONS_OPENAI_MODEL_MINI_ONLY"));
        radioLocal.setSelected(true);
        updateModeVisibility();
    }

    private JPanel buildOpenAiSection() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(Res.getString("AI_OPTIONS_OPENAI_TITLE")));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.LINE_START;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(Res.getString("AI_OPTIONS_API_KEY")), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(fieldApiKey, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(Res.getString("AI_OPTIONS_MODEL")), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(comboOpenAiModel, c);

        return p;
    }

    private JPanel buildOllamaSection() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(Res.getString("AI_OPTIONS_OLLAMA_TITLE")));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.LINE_START;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(Res.getString("AI_OPTIONS_BASE_URL")), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(fieldOllamaUrl, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(Res.getString("AI_OPTIONS_MODEL")), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(fieldOllamaModel, c);

        return p;
    }

    private JPanel buildCloudSection() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(Res.getString("AI_OPTIONS_CLOUD_TITLE")));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.LINE_START;

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel hint = new JLabel(htmlWrap(Res.getString("AI_OPTIONS_CLOUD_URL_HINT"), 520));
        p.add(hint, c);

        c.gridy = 1;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(Res.getString("AI_OPTIONS_CLOUD_PROCESS_URL")), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(fieldCloudProcessUrl, c);

        c.gridx = 0;
        c.gridy = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(Res.getString("AI_OPTIONS_CLOUD_ANON_KEY")), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(fieldCloudAnonKey, c);
        fieldCloudAnonKey.setToolTipText(Res.getString("AI_OPTIONS_CLOUD_ANON_KEY_HINT"));

        c.gridx = 0;
        c.gridy = 3;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(Res.getString("AI_OPTIONS_CLOUD_CHECK_URL")), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(fieldCloudCheckUrl, c);

        c.gridx = 0;
        c.gridy = 4;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(Res.getString("AI_OPTIONS_CLOUD_FETCH_FULL_URL")), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(fieldCloudFetchFullUrl, c);

        c.gridx = 0;
        c.gridy = 5;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(Res.getString("AI_OPTIONS_CLOUD_STRIPE_MINI_URL")), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(fieldCloudStripeMiniUrl, c);

        c.gridx = 0;
        c.gridy = 6;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(new JLabel(htmlWrap(Res.getString("AI_OPTIONS_CLOUD_STRIPE_PRICES_HINT"), 520)), c);

        c.gridy = 7;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(Res.getString("AI_OPTIONS_CLOUD_STRIPE_PRICES_URL")), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(fieldCloudStripePricesUrl, c);

        return p;
    }

    private static String htmlWrap(String s, int maxWidthPx) {
        return "<html><body style='width: " + maxWidthPx + "px'>" + s + "</body></html>";
    }

    private void updateModeVisibility() {
        boolean local = radioLocal.isSelected();
        setPanelDeepEnabled(panelOpenAi, local);
        setPanelDeepEnabled(panelOllama, false);
        panelOpenAi.setVisible(local);
        panelOllama.setVisible(false);
        panelCloud.setVisible(!local);
        setPanelDeepEnabled(panelCloud, !local);
        revalidate();
    }

    private static void setPanelDeepEnabled(javax.swing.JPanel panel, boolean enabled) {
        panel.setEnabled(enabled);
        for (java.awt.Component comp : panel.getComponents()) {
            comp.setEnabled(enabled);
        }
    }

    public void loadSettings(Prefs prefs) {
        if (prefs == null) {
            return;
        }
        if (Prefs.AI_EXTRACTION_MODE_CLOUD.equals(prefs.getAiExtractionMode())) {
            radioCloud.setSelected(true);
        } else {
            radioLocal.setSelected(true);
        }
        fieldApiKey.setText(prefs.getOpenAiApiKey());
        String openAiModel = prefs.getOpenAiModel();
        comboOpenAiModel.setSelectedItem(openAiModel);
        ((JTextField) comboOpenAiModel.getEditor().getEditorComponent()).setText(openAiModel);
        fieldOllamaUrl.setText(prefs.getOllamaBaseUrl());
        fieldOllamaModel.setText(prefs.getOllamaModel());
        fieldCloudProcessUrl.setText(prefs.getCloudProcessIndexUrl());
        fieldCloudAnonKey.setText(SupabaseAiClient.normalizeSupabasePublicAnonKey(prefs.getCloudSupabaseAnonKey()));
        fieldCloudCheckUrl.setText(prefs.getCloudCheckPaymentUrl());
        fieldCloudFetchFullUrl.setText(prefs.getCloudFetchFullResultsUrl());
        fieldCloudStripeMiniUrl.setText(prefs.getCloudStripeCheckoutMiniUrl());
        fieldCloudStripePricesUrl.setText(prefs.getCloudStripePricesUrl());
        updateModeVisibility();
    }

    public void saveSettings(Prefs prefs) {
        if (prefs == null) {
            return;
        }
        prefs.setAiExtractionModeExplicitlyChosen(true);
        if (radioCloud.isSelected()) {
            prefs.setAiExtractionMode(Prefs.AI_EXTRACTION_MODE_CLOUD);
        } else {
            prefs.setAiExtractionMode(Prefs.AI_EXTRACTION_MODE_LOCAL);
            prefs.setAiProvider(PROVIDER_OPENAI);
        }
        prefs.setOpenAiApiKey(new String(fieldApiKey.getPassword()));
        prefs.setOpenAiModel(
                ((JTextField) comboOpenAiModel.getEditor().getEditorComponent()).getText());
        prefs.setOllamaBaseUrl(fieldOllamaUrl.getText().trim());
        prefs.setOllamaModel(fieldOllamaModel.getText().trim());
        prefs.setCloudProcessIndexUrl(fieldCloudProcessUrl.getText().trim());
        prefs.setCloudSupabaseAnonKey(
                SupabaseAiClient.normalizeSupabasePublicAnonKey(new String(fieldCloudAnonKey.getPassword())));
        prefs.setCloudCheckPaymentUrl(fieldCloudCheckUrl.getText().trim());
        prefs.setCloudFetchFullResultsUrl(fieldCloudFetchFullUrl.getText().trim());
        prefs.setCloudStripeCheckoutMiniUrl(fieldCloudStripeMiniUrl.getText().trim());
        prefs.setCloudStripePricesUrl(fieldCloudStripePricesUrl.getText().trim());
        if (radioCloud.isSelected()) {
            prefs.setCloudProcessIndexModel(Prefs.CLOUD_PROCESS_INDEX_MODEL_STANDARD);
        }
    }
}
