/*
 * CloudIndexResult.java
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
