/*
 * PdfPageLabelResolverTest.java
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

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.flavianopetrocchi.jpdfbookmarks.ai.model.AiBookmark;
import it.flavianopetrocchi.jpdfbookmarks.bookmark.Bookmark;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;
import org.junit.jupiter.api.Test;

class PdfPageLabelResolverTest {

    @Test
    void resolvesRomanRawLabelToPhysicalPage() throws Exception {
        try (PDDocument doc = sampleDocumentWithRomanAndDecimalLabels()) {
            PdfPageLabelResolver resolver = PdfPageLabelResolver.create(doc);
            AiBookmark b = new AiBookmark("Preface", -1);
            b.setPageLabelRaw("ii");

            PdfPageLabelResolver.Resolution out = resolver.resolve(b, 99);

            assertEquals(2, out.physicalPageNumber());
            assertEquals(PdfPageLabelResolver.ResolutionMethod.PAGE_LABEL_RAW, out.method());
            assertEquals("2", out.displayLabel());
        }
    }

    @Test
    void resolvesNumericPageAgainstPageLabelsBeforeOffset() throws Exception {
        try (PDDocument doc = sampleDocumentWithRomanAndDecimalLabels()) {
            PdfPageLabelResolver resolver = PdfPageLabelResolver.create(doc);
            AiBookmark b = new AiBookmark("Chapter 1", 1);

            Bookmark converted = AiModelConverter.toAppBookmark(b, 10, resolver);

            assertEquals(4, converted.getPageNumber());
        }
    }

    @Test
    void fallsBackToOffsetWhenDocumentHasNoExplicitPageLabels() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            AiBookmark b = new AiBookmark("Offset", 2);

            Bookmark converted = AiModelConverter.toAppBookmark(b, 3, PdfPageLabelResolver.create(doc));

            assertEquals(5, converted.getPageNumber());
        }
    }

    @Test
    void unresolvedRawLabelLeavesBookmarkWithoutTarget() throws Exception {
        try (PDDocument doc = sampleDocumentWithRomanAndDecimalLabels()) {
            PdfPageLabelResolver resolver = PdfPageLabelResolver.create(doc);
            AiBookmark b = new AiBookmark("Appendix", null);
            b.setPageLabelRaw("A-12");

            Bookmark converted = AiModelConverter.toAppBookmark(b, 0, resolver);

            assertEquals(-1, converted.getPageNumber());
        }
    }

    @Test
    void numericEquivalentCanMatchRomanPageLabels() throws Exception {
        try (PDDocument doc = sampleDocumentWithRomanAndDecimalLabels()) {
            PdfPageLabelResolver resolver = PdfPageLabelResolver.create(doc);
            AiBookmark b = new AiBookmark("RomanAsInt", 2);

            PdfPageLabelResolver.Resolution out = resolver.resolve(b, 0);

            assertEquals(2, out.physicalPageNumber());
            assertEquals(PdfPageLabelResolver.ResolutionMethod.PAGE_LABEL_NUMERIC, out.method());
        }
    }

    private static PDDocument sampleDocumentWithRomanAndDecimalLabels() {
        PDDocument doc = new PDDocument();
        for (int i = 0; i < 6; i++) {
            doc.addPage(new PDPage());
        }
        PDPageLabels labels = new PDPageLabels(doc);
        PDPageLabelRange roman = new PDPageLabelRange();
        roman.setStyle(PDPageLabelRange.STYLE_ROMAN_LOWER);
        roman.setStart(1);
        labels.setLabelItem(0, roman);
        PDPageLabelRange decimal = new PDPageLabelRange();
        decimal.setStyle(PDPageLabelRange.STYLE_DECIMAL);
        decimal.setStart(1);
        labels.setLabelItem(3, decimal);
        doc.getDocumentCatalog().setPageLabels(labels);
        return doc;
    }
}
