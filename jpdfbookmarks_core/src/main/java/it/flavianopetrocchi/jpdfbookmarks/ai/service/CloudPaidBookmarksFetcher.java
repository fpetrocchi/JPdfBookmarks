package it.flavianopetrocchi.jpdfbookmarks.ai.service;

import it.flavianopetrocchi.jpdfbookmarks.AiPreviewPaymentConfig;
import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import java.util.List;
import java.util.Objects;

/**
 * Chiamate GET verso Supabase per verificare il pagamento e scaricare i segnalibri finali, tramite {@link SupabaseAiClient}.
 */
public final class CloudPaidBookmarksFetcher {

    private final SupabaseAiClient client;

    public CloudPaidBookmarksFetcher(SupabaseAiClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Costruisce il fetcher usando URL e chiave della configurazione di anteprima/pagamento. */
    public static CloudPaidBookmarksFetcher fromAiPreviewPaymentConfig(AiPreviewPaymentConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        return new CloudPaidBookmarksFetcher(
                new SupabaseAiClient(
                        cfg.cloudProcessIndexUrl(),
                        cfg.supabaseAnonKey(),
                        cfg.cloudCheckPaymentUrl(),
                        cfg.cloudFetchFullResultsUrl()));
    }

    public boolean isPaid(String taskId) throws AiOrchestrationException {
        return client.checkPaymentStatus(taskId);
    }

    /** Stato pagamento e segnalibri inclusi nella risposta {@code check-payment} quando {@code paid} è true. */
    public SupabaseAiClient.CheckPaymentResult checkPayment(String taskId) throws AiOrchestrationException {
        return client.checkPayment(taskId);
    }

    public List<AiBookmark> fetchFullBookmarks(String taskId) throws AiOrchestrationException {
        return client.fetchBookmarksForTask(taskId);
    }
}
