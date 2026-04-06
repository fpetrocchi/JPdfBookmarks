package it.flavianopetrocchi.jpdfbookmarks;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/**
 * Pannello opzioni IA (OpenAI) usato da {@link OptionsDlg}.
 * <p>
 * Il ramo Ollama ({@link #PROVIDER_OLLAMA}, {@link #buildOllamaSection()}, campi URL/modello) resta nel codice ma non è
 * mostrato in UI: l'utente non può selezionarlo finché non verrà riattivato.
 */
public class AiOptionsPanel extends JPanel {

    private static final String PROVIDER_OPENAI = "OPENAI";
    /** Conservato per {@link Prefs} / {@link it.flavianopetrocchi.jpdfbookmarks.ai.AiOrchestratorFactory}; non in combo UI. */
    @SuppressWarnings("unused")
    private static final String PROVIDER_OLLAMA = "OLLAMA";

    private static final String[] OPENAI_MODEL_PRESETS = {
        "gpt-5.4-mini", "gpt-5.4", "gpt-4o-mini", "gpt-4o"
    };

    private final JPasswordField fieldApiKey = new JPasswordField(32);
    private final JComboBox<String> comboOpenAiModel = new JComboBox<>(OPENAI_MODEL_PRESETS);
    private final JTextField fieldOllamaUrl = new JTextField(32);
    private final JTextField fieldOllamaModel = new JTextField(32);

    private final JPanel panelOpenAi;
    private final JPanel panelOllama;

    public AiOptionsPanel() {
        super(new GridBagLayout());

        panelOpenAi = buildOpenAiSection();
        panelOllama = buildOllamaSection();

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.LINE_START;

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        add(panelOpenAi, c);

        comboOpenAiModel.setEditable(true);

        updateSectionEnabling();
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

    private void updateSectionEnabling() {
        setPanelDeepEnabled(panelOpenAi, true);
        setPanelDeepEnabled(panelOllama, false);
    }

    private static void setPanelDeepEnabled(JPanel panel, boolean enabled) {
        panel.setEnabled(enabled);
        for (java.awt.Component comp : panel.getComponents()) {
            comp.setEnabled(enabled);
        }
    }

    /**
     * Carica i valori da {@link Prefs} nei controlli e aggiorna abilitazione sezioni.
     */
    public void loadSettings(Prefs prefs) {
        if (prefs == null) {
            return;
        }
        fieldApiKey.setText(prefs.getOpenAiApiKey());
        String openAiModel = prefs.getOpenAiModel();
        comboOpenAiModel.setSelectedItem(openAiModel);
        ((JTextField) comboOpenAiModel.getEditor().getEditorComponent()).setText(openAiModel);
        fieldOllamaUrl.setText(prefs.getOllamaBaseUrl());
        fieldOllamaModel.setText(prefs.getOllamaModel());
        updateSectionEnabling();
    }

    /**
     * Scrive i controlli in {@link Prefs} (solo memoria; {@link OptionsDlg} non invoca il flush esplicito).
     */
    public void saveSettings(Prefs prefs) {
        if (prefs == null) {
            return;
        }
        prefs.setAiProvider(PROVIDER_OPENAI);
        prefs.setOpenAiApiKey(new String(fieldApiKey.getPassword()));
        prefs.setOpenAiModel(
                ((JTextField) comboOpenAiModel.getEditor().getEditorComponent()).getText());
        prefs.setOllamaBaseUrl(fieldOllamaUrl.getText().trim());
        prefs.setOllamaModel(fieldOllamaModel.getText().trim());
    }

}
