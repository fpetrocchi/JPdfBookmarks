package it.flavianopetrocchi.jpdfbookmarks;

import java.awt.Color;
import javax.swing.Icon;
import javax.swing.UIManager;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.swing.FontIcon;

/** Vector icons (Ikonli) tinted for the current look and feel. */
public final class UiIcons {

    private UiIcons() {}

    public static Icon of(Ikon ikon, int sizePx) {
        FontIcon icon = FontIcon.of(ikon, sizePx);
        Color c = UIManager.getColor("Label.foreground");
        if (c != null) {
            icon.setIconColor(c);
        }
        return icon;
    }
}
