/*
 * ProcessIndexResult.java
 *
 * Copyright (c) 2010 Flaviano Petrocchi <flavianopetrocchi at gmail.com>.
 * All rights reserved.
 *
 * This file is part of JPdfBookmarks.
 *
 * JPdfBookmarks is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPdfBookmarks is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JPdfBookmarks.  If not, see <http://www.gnu.org/licenses/>.
 */

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

    /**
     * Dopo {@code extend_paid_task_id}: stessa risposta cloud ma i segnalibri sono l'indice completo e non si riapre
     * il flusso anteprima/pagamento.
     */
    public static ProcessIndexResult fromCloudPaidExtension(CloudIndexResult cloud) {
        return new ProcessIndexResult(cloud.bookmarks(), cloud.taskId(), null, false);
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
