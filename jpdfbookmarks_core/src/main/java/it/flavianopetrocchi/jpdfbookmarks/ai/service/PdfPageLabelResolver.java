/*
 * PdfPageLabelResolver.java
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;

/**
 * Resolves AI page references against explicit PDF page labels ({@code /PageLabels}) and falls back to the historical
 * constant offset strategy when labels are absent or a bookmark cannot be matched.
 */
public final class PdfPageLabelResolver {

    public enum ResolutionMethod {
        PAGE_LABEL_RAW,
        PAGE_LABEL_NUMERIC,
        OFFSET_FALLBACK,
        UNRESOLVED
    }

    public record Resolution(Integer physicalPageNumber, String displayLabel, ResolutionMethod method) {}

    private static final PdfPageLabelResolver UNAVAILABLE =
            new PdfPageLabelResolver(new String[0], Collections.emptyMap(), Collections.emptyMap());

    private final String[] labelsByPageIndex;
    private final Map<String, List<Integer>> exactLabelLookup;
    private final Map<Integer, List<Integer>> numericLabelLookup;

    private PdfPageLabelResolver(
            String[] labelsByPageIndex,
            Map<String, List<Integer>> exactLabelLookup,
            Map<Integer, List<Integer>> numericLabelLookup) {
        this.labelsByPageIndex = labelsByPageIndex != null ? labelsByPageIndex.clone() : new String[0];
        this.exactLabelLookup = exactLabelLookup;
        this.numericLabelLookup = numericLabelLookup;
    }

    public static PdfPageLabelResolver unavailable() {
        return UNAVAILABLE;
    }

    public static PdfPageLabelResolver create(PDDocument document) {
        if (document == null) {
            return unavailable();
        }
        try {
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            if (catalog == null || !catalog.getCOSObject().containsKey(COSName.PAGE_LABELS)) {
                return unavailable();
            }
            PDPageLabels labels = catalog.getPageLabels();
            if (labels == null) {
                return unavailable();
            }
            String[] byIndex = labels.getLabelsByPageIndices();
            if (byIndex == null || byIndex.length == 0) {
                return unavailable();
            }
            Map<String, List<Integer>> exact = new HashMap<>();
            Map<Integer, List<Integer>> numeric = new HashMap<>();
            for (int i = 0; i < byIndex.length; i++) {
                String label = byIndex[i];
                String norm = normalizeLabel(label);
                if (!norm.isEmpty()) {
                    exact.computeIfAbsent(norm, k -> new ArrayList<>()).add(i);
                }
                Integer parsedNumeric = parseWholeNumericOrRoman(label);
                if (parsedNumeric != null) {
                    numeric.computeIfAbsent(parsedNumeric, k -> new ArrayList<>()).add(i);
                }
            }
            return new PdfPageLabelResolver(byIndex, freezeExact(exact), freezeNumeric(numeric));
        } catch (IOException | RuntimeException e) {
            return unavailable();
        }
    }

    public boolean isAvailable() {
        return labelsByPageIndex.length > 0;
    }

    public Resolution resolve(AiBookmark bookmark, int fallbackOffset) {
        Objects.requireNonNull(bookmark, "bookmark");
        String raw = trimOrEmpty(bookmark.getPageLabelRaw());
        Integer pageNumber = bookmark.getPageNumber();
        Integer offsetFallback = computeOffsetFallback(pageNumber, fallbackOffset);

        if (!raw.isEmpty()) {
            List<Integer> exactMatches = exactLabelLookup.get(normalizeLabel(raw));
            Integer exactPage = chooseCandidate(exactMatches, offsetFallback);
            if (exactPage != null) {
                return resolved(exactPage, ResolutionMethod.PAGE_LABEL_RAW);
            }
            Integer rawNumeric = parseWholeNumericOrRoman(raw);
            Integer numericPage = chooseCandidate(numericLabelLookup.get(rawNumeric), offsetFallback);
            if (numericPage != null) {
                return resolved(numericPage, ResolutionMethod.PAGE_LABEL_NUMERIC);
            }
        }

        if (pageNumber != null && pageNumber > 0) {
            Integer numericPage = chooseCandidate(numericLabelLookup.get(pageNumber), offsetFallback);
            if (numericPage != null) {
                return resolved(numericPage, ResolutionMethod.PAGE_LABEL_NUMERIC);
            }
        }

        if (offsetFallback != null) {
            return new Resolution(offsetFallback, String.valueOf(offsetFallback), ResolutionMethod.OFFSET_FALLBACK);
        }

        String display = !raw.isEmpty() ? raw : pageNumber != null ? String.valueOf(pageNumber) : "";
        return new Resolution(null, display, ResolutionMethod.UNRESOLVED);
    }

    private static Map<String, List<Integer>> freezeExact(Map<String, List<Integer>> source) {
        Map<String, List<Integer>> out = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : source.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<Integer, List<Integer>> freezeNumeric(Map<Integer, List<Integer>> source) {
        Map<Integer, List<Integer>> out = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : source.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Resolution resolved(int zeroBasedPageIndex, ResolutionMethod method) {
        int oneBased = zeroBasedPageIndex + 1;
        return new Resolution(oneBased, String.valueOf(oneBased), method);
    }

    private static Integer chooseCandidate(List<Integer> zeroBasedCandidates, Integer preferredOneBasedPage) {
        if (zeroBasedCandidates == null || zeroBasedCandidates.isEmpty()) {
            return null;
        }
        if (preferredOneBasedPage == null || preferredOneBasedPage < 1 || zeroBasedCandidates.size() == 1) {
            return zeroBasedCandidates.get(0);
        }
        Integer best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Integer zeroBased : zeroBasedCandidates) {
            if (zeroBased == null) {
                continue;
            }
            int oneBased = zeroBased + 1;
            int d = Math.abs(oneBased - preferredOneBasedPage);
            if (best == null || d < bestDistance || (d == bestDistance && zeroBased < best)) {
                best = zeroBased;
                bestDistance = d;
            }
        }
        return best;
    }

    private static Integer computeOffsetFallback(Integer pageNumber, int offset) {
        if (pageNumber == null || pageNumber < 1) {
            return null;
        }
        return pageNumber + offset;
    }

    private static String normalizeLabel(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.toLowerCase(Locale.ROOT);
    }

    private static String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }

    /**
     * Converts a whole-page label to an integer only for plain decimal values or pure Roman numerals.
     * Labels with prefixes (for example {@code A-12}) are intentionally not reduced to a number here because exact
     * label matching is safer.
     */
    static Integer parseWholeNumericOrRoman(String raw) {
        String t = trimOrEmpty(raw);
        if (t.isEmpty()) {
            return null;
        }
        if (t.matches("\\d+")) {
            try {
                return Integer.parseInt(t, 10);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (!t.matches("(?i)[ivxlcdm]+")) {
            return null;
        }
        int total = 0;
        int prev = 0;
        String u = t.toUpperCase(Locale.ROOT);
        for (int i = u.length() - 1; i >= 0; i--) {
            int val = romanValue(u.charAt(i));
            if (val <= 0) {
                return null;
            }
            if (val < prev) {
                total -= val;
            } else {
                total += val;
                prev = val;
            }
        }
        return total > 0 ? total : null;
    }

    private static int romanValue(char c) {
        return switch (c) {
            case 'I' -> 1;
            case 'V' -> 5;
            case 'X' -> 10;
            case 'L' -> 50;
            case 'C' -> 100;
            case 'D' -> 500;
            case 'M' -> 1000;
            default -> -1;
        };
    }
}
