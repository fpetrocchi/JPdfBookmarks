package it.flavianopetrocchi.jpdfbookmarks.ai.service;

import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Risposta dell'endpoint {@code process-index}: identificativo task e segnalibri (anteprima o lista completa).
 */
public record CloudIndexResult(String taskId, List<AiBookmark> bookmarks) {

    public CloudIndexResult {
        taskId = taskId != null ? taskId.trim() : "";
        bookmarks =
                bookmarks != null ? Collections.unmodifiableList(bookmarks) : Collections.emptyList();
    }

    public boolean hasTaskId() {
        return taskId != null && !taskId.isEmpty();
    }
}
