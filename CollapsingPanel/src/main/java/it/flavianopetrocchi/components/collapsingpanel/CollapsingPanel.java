package it.flavianopetrocchi.components.collapsingpanel;

import it.flavianopetrocchi.reshelper.ResHelper;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.swing.FontIcon;

/**
 * Pannello laterale collassabile con strip verticale di icone per passare tra le schede interne (stile viewer PDF).
 */
public class CollapsingPanel extends JPanel {

    private static class SplitterContainerListener implements ContainerListener {

        JButton closePanelButton;
        JButton openPanelButton;
        Border emptyBorder = BorderFactory.createEmptyBorder(1, 1, 1, 1);

        public SplitterContainerListener(JButton closePanelButton, JButton openPanelButton) {
            this.closePanelButton = closePanelButton;
            this.openPanelButton = openPanelButton;
        }

        public void componentAdded(ContainerEvent e) {
            closePanelButton.setBorder(emptyBorder);
        }

        public void componentRemoved(ContainerEvent e) {
            openPanelButton.setBorder(emptyBorder);
        }
    }

    private static final String PROPERTIES_PATH = "it/flavianopetrocchi/components/collapsingpanel/CollapsingPanel";
    private static final Color SELECTED_TAB_BORDER = new Color(230, 130, 40);
    private static final int SELECTOR_STRIP_WIDTH = 44;

    protected final JPanel innerPanelsContainer = new JPanel();
    private final JPanel selectorStrip = new JPanel();
    private final JPanel openLeftPanelContainer = new JPanel();
    private final Map<String, JToggleButton> togglesByPanelName = new LinkedHashMap<>();
    private final ButtonGroup selectorGroup = new ButtonGroup();
    private final CopyOnWriteArrayList<Consumer<String>> panelSelectionListeners = new CopyOnWriteArrayList<>();
    private final Component selectorBottomGlue = Box.createVerticalGlue();

    private JSplitPane containerSplitter;
    private int dividerLocation;
    protected final CardLayout cardLayout = new CardLayout();
    private ResHelper resHelper;
    private int state = PANEL_OPENED;
    private boolean firstRestore = true;
    private String selectedPanelName;

    public static final int COLLAPSING_PANEL_LEFT = 0, COLLAPSING_PANEL_RIGHT = 1;

    public static final int PANEL_COLLAPSED = 0, PANEL_OPENED = 1;

    public CollapsingPanel(JSplitPane containerSplitter) {
        this(containerSplitter, COLLAPSING_PANEL_LEFT);
    }

    public CollapsingPanel(JSplitPane containerSplitter, int collapsingSide) {
        this.containerSplitter = containerSplitter;
        initComponents();
    }

    private void initComponents() {
        innerPanelsContainer.setLayout(cardLayout);

        selectorStrip.setLayout(new BoxLayout(selectorStrip, BoxLayout.Y_AXIS));
        selectorStrip.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0xc8, 0xc8, 0xc8)),
                        BorderFactory.createEmptyBorder(6, 4, 6, 4)));
        Color stripBg = UIManager.getColor("Panel.background");
        if (stripBg != null) {
            selectorStrip.setBackground(stripBg);
            selectorStrip.setOpaque(true);
        }
        Dimension stripW = new Dimension(SELECTOR_STRIP_WIDTH, 0);
        selectorStrip.setPreferredSize(stripW);
        selectorStrip.setMinimumSize(stripW);
        selectorStrip.setMaximumSize(new Dimension(SELECTOR_STRIP_WIDTH, Integer.MAX_VALUE));

        resHelper = new ResHelper(getClass(), PROPERTIES_PATH);

        openLeftPanelContainer.setBorder(BorderFactory.createEmptyBorder(5, 1, 0, 1));
        openLeftPanelContainer.setLayout(new BoxLayout(openLeftPanelContainer, BoxLayout.Y_AXIS));
        JButton openPanelButton = new JButton(stripIcon(MaterialDesignC.CHEVRON_DOUBLE_RIGHT, 18));
        openPanelButton.setToolTipText(resHelper.getString("OPEN_PANEL_BUTTON_DESCR"));
        openPanelButton.setContentAreaFilled(false);
        openPanelButton.setRolloverEnabled(true);
        openPanelButton.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        openPanelButton.addMouseListener(new ButtonRolloverListener(openPanelButton));
        openPanelButton.addActionListener(
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        setPanelState(PANEL_OPENED);
                    }
                });
        openLeftPanelContainer.add(openPanelButton);

        JButton closePanelButton = new JButton(stripIcon(MaterialDesignC.CHEVRON_DOUBLE_LEFT, 18));
        closePanelButton.addMouseListener(new ButtonRolloverListener(closePanelButton));
        closePanelButton.setContentAreaFilled(false);
        closePanelButton.setToolTipText(resHelper.getString("CLOSE_PANEL_BUTTON_DESCR"));
        closePanelButton.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        closePanelButton.addActionListener(
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        setPanelState(PANEL_COLLAPSED);
                    }
                });

        JPanel closeRow = new JPanel(new BorderLayout());
        closeRow.setBorder(BorderFactory.createEmptyBorder(3, 4, 4, 4));
        closeRow.add(closePanelButton, BorderLayout.EAST);

        JPanel centerArea = new JPanel(new BorderLayout());
        centerArea.add(closeRow, BorderLayout.NORTH);
        centerArea.add(innerPanelsContainer, BorderLayout.CENTER);

        setLayout(new BorderLayout());
        add(selectorStrip, BorderLayout.WEST);
        add(centerArea, BorderLayout.CENTER);

        containerSplitter.addContainerListener(new SplitterContainerListener(closePanelButton, openPanelButton));
    }

    private static Icon stripIcon(Ikon ikon, int sizePx) {
        return new StripThemedFontIcon(ikon, sizePx);
    }

    /**
     * Same behavior as {@code UiIcons} in the main app: LAF-aware and disabled-state-aware painting
     * for toolbar-style strip controls.
     */
    private static final class StripThemedFontIcon implements Icon {

        private final FontIcon delegate;

        StripThemedFontIcon(Ikon ikon, int sizePx) {
            delegate = FontIcon.of(ikon, sizePx);
        }

        private static Color resolveIconColor(Component c) {
            boolean enabled = c == null || c.isEnabled();
            if (!enabled) {
                Color d = UIManager.getColor("Button.disabledForeground");
                if (d != null) {
                    return d;
                }
                d = UIManager.getColor("Label.disabledForeground");
                return d != null ? d : new Color(0x80_80_80);
            }
            Color fg = UIManager.getColor("Button.foreground");
            if (fg != null) {
                return fg;
            }
            fg = UIManager.getColor("Label.foreground");
            return fg != null ? fg : Color.BLACK;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            delegate.setIconColor(resolveIconColor(c));
            delegate.paintIcon(c, g, x, y);
        }

        @Override
        public int getIconWidth() {
            return delegate.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return delegate.getIconHeight();
        }
    }

    public void addPanelSelectionListener(Consumer<String> listener) {
        if (listener != null) {
            panelSelectionListeners.add(listener);
        }
    }

    private void firePanelSelectionListeners(String panelName) {
        for (Consumer<String> c : panelSelectionListeners) {
            c.accept(panelName);
        }
    }

    public String getSelectedInnerPanelName() {
        if (selectedPanelName != null) {
            return selectedPanelName;
        }
        return togglesByPanelName.keySet().stream().findFirst().orElse(null);
    }

    public void setSelectedInnerPanelName(String name) {
        if (name == null || togglesByPanelName.isEmpty()) {
            return;
        }
        if (!togglesByPanelName.containsKey(name)) {
            name = togglesByPanelName.keySet().iterator().next();
        }
        JToggleButton b = togglesByPanelName.get(name);
        if (b == null) {
            return;
        }
        if (b.isSelected()) {
            activatePanel(name, true);
        } else {
            b.setSelected(true);
        }
    }

    private void activatePanel(String name, boolean notifyListeners) {
        cardLayout.show(innerPanelsContainer, name);
        selectedPanelName = name;
        refreshToggleBorders();
        if (notifyListeners) {
            firePanelSelectionListeners(name);
        }
    }

    private void refreshToggleBorders() {
        Border empty = BorderFactory.createEmptyBorder(2, 2, 2, 2);
        Border sel =
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(SELECTED_TAB_BORDER, 2, true),
                        BorderFactory.createEmptyBorder(0, 0, 0, 0));
        for (JToggleButton tb : togglesByPanelName.values()) {
            tb.setBorder(tb.isSelected() ? sel : empty);
        }
    }

    public void addInnerPanel(JPanel innerPanel, String name) {
        addInnerPanel(innerPanel, name, null, null);
    }

    public void addInnerPanel(JPanel innerPanel, String name, Icon tabIcon) {
        addInnerPanel(innerPanel, name, tabIcon, null);
    }

    public void addInnerPanel(JPanel innerPanel, String name, Icon tabIcon, String toolTipText) {
        innerPanelsContainer.add(innerPanel, name);

        Icon icon = tabIcon != null ? tabIcon : createFallbackTabIcon(togglesByPanelName.size() + 1);
        JToggleButton tb = new JToggleButton(icon);
        tb.setFocusPainted(false);
        tb.setToolTipText(toolTipText != null ? toolTipText : name);
        tb.setMargin(new Insets(4, 4, 4, 4));
        tb.setContentAreaFilled(false);
        tb.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        tb.setAlignmentX(Component.CENTER_ALIGNMENT);

        tb.addActionListener(
                e -> {
                    if (tb.isSelected()) {
                        activatePanel(name, true);
                    } else {
                        boolean any =
                                togglesByPanelName.values().stream()
                                        .anyMatch(AbstractButton::isSelected);
                        if (!any) {
                            tb.setSelected(true);
                        } else {
                            refreshToggleBorders();
                        }
                    }
                });

        selectorGroup.add(tb);
        togglesByPanelName.put(name, tb);
        if (selectorBottomGlue.getParent() == selectorStrip) {
            selectorStrip.remove(selectorBottomGlue);
        }
        selectorStrip.add(tb);
        selectorStrip.add(Box.createVerticalStrut(4));
        selectorStrip.add(selectorBottomGlue);

        if (togglesByPanelName.size() == 1) {
            tb.setSelected(true);
        }
    }

    private static Icon createFallbackTabIcon(int index) {
        int s = 20;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x6a, 0x75, 0x82));
            g.setStroke(new BasicStroke(1.2f));
            g.drawOval(2, 2, s - 5, s - 5);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            String t = Integer.toString(Math.min(9, Math.max(1, index)));
            int w = g.getFontMetrics().stringWidth(t);
            g.drawString(t, (s - w) / 2, 15);
        } finally {
            g.dispose();
        }
        return new ImageIcon(img);
    }

    private class ButtonRolloverListener extends MouseAdapter {

        JButton btn;

        public ButtonRolloverListener(JButton btn) {
            this.btn = btn;
        }

        @Override
        public void mouseEntered(MouseEvent e) {
            super.mouseEntered(e);
            btn.setBorder(BorderFactory.createLineBorder(Color.black));
        }

        @Override
        public void mouseExited(MouseEvent e) {
            super.mouseExited(e);
            btn.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        }
    }

    public int getPanelState() {
        return state;
    }

    public int setPanelState(int state) {
        int oldState = this.state;
        if (state == PANEL_COLLAPSED) {
            if (oldState == PANEL_OPENED) {
                if (!firstRestore) {
                    dividerLocation = containerSplitter.getDividerLocation();
                } else {
                    firstRestore = false;
                }
                containerSplitter.setLeftComponent(openLeftPanelContainer);
                containerSplitter.setOneTouchExpandable(false);
                containerSplitter.setEnabled(false);
            }
        } else {
            if (oldState == PANEL_COLLAPSED) {
                if (dividerLocation >= 0) {
                    containerSplitter.setDividerLocation(dividerLocation);
                }
                containerSplitter.setLeftComponent(CollapsingPanel.this);
                containerSplitter.setOneTouchExpandable(true);
                containerSplitter.setEnabled(true);
            }
        }
        this.state = state;
        return oldState;
    }

    public int getDividerLocation() {
        if (state == PANEL_OPENED) {
            return containerSplitter.getDividerLocation();
        }
        return dividerLocation;
    }

    public void setDividerLocation(int location) {
        dividerLocation = location;
        if (location < 0) {
            return;
        }
        if (state == PANEL_OPENED) {
            SwingUtilities.invokeLater(
                    () -> {
                        if (state == PANEL_OPENED && dividerLocation >= 0) {
                            containerSplitter.setDividerLocation(dividerLocation);
                        }
                    });
        }
    }

    public void updateComponentsUI() {
        SwingUtilities.updateComponentTreeUI(this);
        SwingUtilities.updateComponentTreeUI(openLeftPanelContainer);
    }

    public CardLayout getCardLayout() {
        return cardLayout;
    }

    public JPanel getCardsContainerPanel() {
        return innerPanelsContainer;
    }
}
