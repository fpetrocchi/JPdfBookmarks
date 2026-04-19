/*
 * AiModelConverter.java
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
import it.flavianopetrocchi.jpdfbookmarks.bookmark.Bookmark;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps AI/LLM bookmark DTOs ({@link AiBookmark}) to the Swing/PDF {@link Bookmark} tree used by JPdfBookmarks.
 * <p>
 * {@link Bookmark} extends {@link javax.swing.tree.DefaultMutableTreeNode}; child nodes are attached with
 * {@link Bookmark#add(javax.swing.tree.MutableTreeNode)} (same pattern as {@code cloneBookmarkWithChildren}).
 */
public final class AiModelConverter {

    private AiModelConverter() {
    }

    /**
     * Converts each top-level {@link AiBookmark} into a root {@link Bookmark}. Order is preserved.
     * {@code null} input or entries are skipped where noted.
     *
     * @param aiBookmarks roots of one or more trees; may be {@code null} or contain {@code null} elements
     * @param offset      value added to each {@link AiBookmark#getPageNumber()} that is {@code > 0} when setting the
     *                    destination page on {@link Bookmark} (correction after raw AI extraction)
     * @return mutable list of native bookmarks (empty if input is null or empty)
     */
    public static List<Bookmark> toAppBookmarks(List<AiBookmark> aiBookmarks, int offset) {
        return toAppBookmarks(aiBookmarks, offset, PdfPageLabelResolver.unavailable());
    }

    /**
     * Converts each top-level {@link AiBookmark} into a root {@link Bookmark}, resolving PDF logical page labels when
     * possible and falling back to the historical constant offset strategy otherwise.
     */
    public static List<Bookmark> toAppBookmarks(
            List<AiBookmark> aiBookmarks, int offset, PdfPageLabelResolver pageLabelResolver) {
        if (aiBookmarks == null || aiBookmarks.isEmpty()) {
            return new ArrayList<>();
        }
        PdfPageLabelResolver resolver =
                pageLabelResolver != null ? pageLabelResolver : PdfPageLabelResolver.unavailable();
        List<Bookmark> roots = new ArrayList<>(aiBookmarks.size());
        for (AiBookmark ai : aiBookmarks) {
            if (ai != null) {
                roots.add(toAppBookmark(ai, offset, resolver));
            }
        }
        return roots;
    }

    /**
     * Converte un sottoalbero {@link AiBookmark} senza scostamento di pagina ({@code offset = 0}).
     *
     * @param ai radice non null dell'albero da convertire
     */
    public static Bookmark toAppBookmark(AiBookmark ai) {
        return toAppBookmark(ai, 0, PdfPageLabelResolver.unavailable());
    }

    /**
     * Converte un sottoalbero {@link AiBookmark} in {@link Bookmark}. Per ogni nodo, se {@code page_number &gt; 0},
     * il valore scritto sul segnalibro è {@code page_number + offset}; altrimenti il numero resta quello dell'IA (es. -1).
     *
     * @param ai     radice non null dell'albero da convertire
     * @param offset intero sommato ai soli {@code page_number} positivi (correzione applicata lato applicazione)
     */
    public static Bookmark toAppBookmark(AiBookmark ai, int offset) {
        return toAppBookmark(ai, offset, PdfPageLabelResolver.unavailable());
    }

    /**
     * Converts one {@link AiBookmark} subtree into a {@link Bookmark}, preferring explicit PDF page labels and using the
     * offset only as fallback.
     */
    public static Bookmark toAppBookmark(AiBookmark ai, int offset, PdfPageLabelResolver pageLabelResolver) {
        Objects.requireNonNull(ai, "ai");
        PdfPageLabelResolver resolver =
                pageLabelResolver != null ? pageLabelResolver : PdfPageLabelResolver.unavailable();
        Bookmark node = new Bookmark();
        String title = ai.getTitle();
        if (title != null && !title.isEmpty()) {
            node.setTitle(title);
        }
        Integer pageNumber = ai.getPageNumber();
        Integer resolvedPageNumber = resolveTargetPageNumber(ai, offset, resolver);
        if (resolvedPageNumber != null) {
            node.setPageNumber(resolvedPageNumber);
        } else if (pageNumber != null) {
            node.setPageNumber(pageNumber);
        }
        List<AiBookmark> children = ai.getChildren();
        if (children != null) {
            for (AiBookmark child : children) {
                if (child != null) {
                    node.add(toAppBookmark(child, offset, resolver));
                }
            }
        }
        return node;
    }

    public static Integer resolveTargetPageNumber(AiBookmark ai, int offset, PdfPageLabelResolver pageLabelResolver) {
        Objects.requireNonNull(ai, "ai");
        PdfPageLabelResolver resolver =
                pageLabelResolver != null ? pageLabelResolver : PdfPageLabelResolver.unavailable();
        return resolver.resolve(ai, offset).physicalPageNumber();
    }
}
