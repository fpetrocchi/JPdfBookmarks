package it.flavianopetrocchi.jpdfbookmarks;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.UIManager;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.swing.FontIcon;

/**
 * Vector icons (Ikonli) tinted from the active look-and-feel on each paint, so enabled/disabled
 * and theme switches (e.g. Flat Dark) stay correct.
 */
public final class UiIcons {

    private UiIcons() {}

    public static Icon of(Ikon ikon, int sizePx) {
        return new ThemedFontIcon(ikon, sizePx);
    }

    private static Color resolveIconColor(Component c) {
        boolean enabled = c == null || c.isEnabled();
        if (!enabled) {
            Color d = UIManager.getColor("Button.disabledForeground");
            if (d != null) {
                return d;
            }
            d = UIManager.getColor("MenuItem.disabledForeground");
            if (d != null) {
                return d;
            }
            d = UIManager.getColor("Label.disabledForeground");
            return d != null ? d : new Color(0x80_80_80);
        }
        if (c instanceof AbstractButton) {
            Color b = UIManager.getColor("Button.foreground");
            if (b != null) {
                return b;
            }
        }
        Color fg = UIManager.getColor("Label.foreground");
        return fg != null ? fg : Color.BLACK;
    }

    private static final class ThemedFontIcon implements Icon {

        private final FontIcon delegate;

        ThemedFontIcon(Ikon ikon, int sizePx) {
            delegate = FontIcon.of(ikon, sizePx);
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
}
