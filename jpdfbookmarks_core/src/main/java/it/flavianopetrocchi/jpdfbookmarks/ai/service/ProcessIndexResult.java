package it.flavianopetrocchi.jpdfbookmarks.ai.service;

import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import java.util.Collections;
import java.util.List;

/**
 * Risultato di {@link AiOrchestrator#processIndex(org.apache.pdfbox.pdmodel.PDDocument, int, int)}: modello locale oppure
 * risposta cloud {@link CloudIndexResult} (task + anteprima segnalibri).
 */
public final class ProcessIndexResult {

    private final List<AiBookmark> bookmarks;
    private final String cloudTaskId;
    private final String checkoutUrl;
    private final boolean cloudSubmit;

    public ProcessIndexResult(List<AiBookmark> bookmarks, String cloudTaskId, String checkoutUrl) {
        this(bookmarks, cloudTaskId, checkoutUrl, false);
    }

    private ProcessIndexResult(
            List<AiBookmark> bookmarks, String cloudTaskId, String checkoutUrl, boolean cloudSubmit) {
        this.bookmarks =
                bookmarks != null ? Collections.unmodifiableList(bookmarks) : Collections.emptyList();
        this.cloudTaskId = cloudTaskId != null ? cloudTaskId.trim() : null;
        this.checkoutUrl = checkoutUrl != null && !checkoutUrl.isBlank() ? checkoutUrl.trim() : null;
        this.cloudSubmit = cloudSubmit;
    }

    public static ProcessIndexResult fromCloudProcessIndex(CloudIndexResult cloud) {
        return new ProcessIndexResult(cloud.bookmarks(), cloud.taskId(), null, true);
    }

    public List<AiBookmark> getBookmarks() {
        return bookmarks;
    }

    public String getCloudTaskId() {
        return cloudTaskId;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    /** {@code true} se il risultato proviene dall'endpoint cloud {@code process-index}. */
    public boolean isCloudSubmit() {
        return cloudSubmit;
    }

    public static ProcessIndexResult localBookmarks(List<AiBookmark> list) {
        return new ProcessIndexResult(list, null, null, false);
    }
}
