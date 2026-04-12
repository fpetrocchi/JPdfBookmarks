package it.flavianopetrocchi.jpdfbookmarks;

import java.util.Objects;

/**
 * URL e chiave necessari a {@link AiPreviewDialog} per checkout Stripe, polling pagamento e download risultati.
 */
public record AiPreviewPaymentConfig(
        String supabaseAnonKey,
        String cloudCheckPaymentUrl,
        String cloudFetchFullResultsUrl,
        String cloudStripeCheckoutMiniUrl,
        String cloudStripeCheckoutAdvancedUrl,
        String cloudProcessIndexUrl) {

    public static AiPreviewPaymentConfig fromPrefs(Prefs prefs) {
        Objects.requireNonNull(prefs, "prefs");
        return new AiPreviewPaymentConfig(
                prefs.getCloudSupabaseAnonKey().trim(),
                prefs.getCloudCheckPaymentUrl().trim(),
                prefs.getCloudFetchFullResultsUrl().trim(),
                prefs.getCloudStripeCheckoutMiniUrl().trim(),
                prefs.getCloudStripeCheckoutAdvancedUrl().trim(),
                prefs.getCloudProcessIndexUrl().trim());
    }
}
