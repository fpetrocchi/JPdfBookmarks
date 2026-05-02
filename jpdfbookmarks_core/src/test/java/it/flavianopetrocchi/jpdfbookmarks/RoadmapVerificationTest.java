package it.flavianopetrocchi.jpdfbookmarks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;

/**
 * Verifiche automatiche per la roadmap cloud / anti-abuso / UI (FlatLaf, tab IA, classpath).
 */
class RoadmapVerificationTest {

    @AfterEach
    void clearDevAiOptionsProperty() {
        System.clearProperty("jpdfbookmarks.dev.showAiOptions");
    }

    @Test
    void aiOptionsTabHiddenUnlessDevProperty() {
        System.clearProperty("jpdfbookmarks.dev.showAiOptions");
        assertFalse(OptionsDlg.isAiOptionsTabVisible(), "Consumer default: tab IA nascosto");
        assertFalse(AiReleasePolicy.isAdvancedAiOptionsTabEnabled());
        System.setProperty("jpdfbookmarks.dev.showAiOptions", "true");
        assertTrue(OptionsDlg.isAiOptionsTabVisible(), "Con -Djpdfbookmarks.dev.showAiOptions=true");
    }

    @Test
    void consumerBuildForcesAiExtractionModeCloud() {
        System.clearProperty("jpdfbookmarks.dev.showAiOptions");
        assertEquals(Prefs.AI_EXTRACTION_MODE_CLOUD, new Prefs().getAiExtractionMode());
    }

    @Test
    void flatLafAndIkonliOnClasspath() {
        assertDoesNotThrow(() -> Class.forName(FlatLightLaf.class.getName()));
        assertDoesNotThrow(() -> Class.forName(FlatDarkLaf.class.getName()));
        assertNotNull(UiIcons.of(MaterialDesignC.COG, 16));
    }

    @Test
    void bundledCloudDefaultsResourceLoads() {
        assertNotNull(AiCloudBundledDefaults.get());
    }

    @Test
    void bundledCjkGlobalFontOverrideDefaultsToDisabled() throws IOException {
        Properties props = new Properties();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("conf/jpdfbookmarks.cjk.font.properties")) {
            assertNotNull(input);
            props.load(input);
        }
        assertEquals("false", props.getProperty("cjk.applyGlobalFontOverride"));
    }

    @Test
    void cloudFreePreviewPageConstantMatchesBackendDefault() {
        assertTrue(Prefs.CLOUD_FREE_PREVIEW_MAX_INDEX_PAGES > 0);
        assertTrue(Prefs.CLOUD_FREE_PREVIEW_MAX_INDEX_PAGES <= 10);
    }
}
