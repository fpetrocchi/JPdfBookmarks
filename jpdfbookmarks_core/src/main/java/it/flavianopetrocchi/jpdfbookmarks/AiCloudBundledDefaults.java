package it.flavianopetrocchi.jpdfbookmarks;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional {@code ai-cloud-defaults.properties} on the classpath (Maven-filtered for release builds).
 * Used when user preferences leave cloud URLs/keys empty.
 */
final class AiCloudBundledDefaults {

    private static final Logger LOG = Logger.getLogger(AiCloudBundledDefaults.class.getName());
    private static final String RESOURCE = "it/flavianopetrocchi/jpdfbookmarks/ai-cloud-defaults.properties";

    private static final AiCloudBundledDefaults INSTANCE = load();

    private final String processIndexUrl;
    private final String anonKey;
    private final String checkPaymentUrl;
    private final String fetchFullUrl;
    private final String stripeCheckoutMiniUrl;
    private final String stripeCheckoutAdvancedUrl;

    private AiCloudBundledDefaults(
            String processIndexUrl,
            String anonKey,
            String checkPaymentUrl,
            String fetchFullUrl,
            String stripeCheckoutMiniUrl,
            String stripeCheckoutAdvancedUrl) {
        this.processIndexUrl = processIndexUrl;
        this.anonKey = anonKey;
        this.checkPaymentUrl = checkPaymentUrl;
        this.fetchFullUrl = fetchFullUrl;
        this.stripeCheckoutMiniUrl = stripeCheckoutMiniUrl;
        this.stripeCheckoutAdvancedUrl = stripeCheckoutAdvancedUrl;
    }

    static AiCloudBundledDefaults get() {
        return INSTANCE;
    }

    boolean isCloudConfigComplete() {
        return !processIndexUrl.isEmpty() && !anonKey.isEmpty();
    }

    String processIndexUrl() {
        return processIndexUrl;
    }

    String anonKey() {
        return anonKey;
    }

    String checkPaymentUrl() {
        return checkPaymentUrl;
    }

    String fetchFullUrl() {
        return fetchFullUrl;
    }

    String stripeCheckoutMiniUrl() {
        return stripeCheckoutMiniUrl;
    }

    String stripeCheckoutAdvancedUrl() {
        return stripeCheckoutAdvancedUrl;
    }

    private static AiCloudBundledDefaults load() {
        Properties p = new Properties();
        try (InputStream in =
                AiCloudBundledDefaults.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not load " + RESOURCE, e);
        }
        return new AiCloudBundledDefaults(
                trimOrEmpty(p.getProperty("ai.cloud.process.index.url")),
                trimOrEmpty(p.getProperty("ai.cloud.anon.key")),
                trimOrEmpty(p.getProperty("ai.cloud.check.payment.url")),
                trimOrEmpty(p.getProperty("ai.cloud.fetch.full.url")),
                trimOrEmpty(p.getProperty("ai.cloud.stripe.checkout.mini.url")),
                trimOrEmpty(p.getProperty("ai.cloud.stripe.checkout.advanced.url")));
    }

    private static String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }
}
