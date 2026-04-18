/*
 * PdfVisionService.java
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

import dev.langchain4j.data.image.Image;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Rende pagine PDF in immagini ad alta risoluzione per modelli di visione (es. estrazione indice via LLM).
 * <p>
 * Il DPI effettivo è scelto dal chiamante: {@link it.flavianopetrocchi.jpdfbookmarks.ai.AiOrchestratorFactory} usa
 * {@link #DEFAULT_RENDER_DPI} (300) per OpenAI e un valore più basso per Ollama, per equilibrio tra nitidezza e tempi
 * di inferenza locali.
 * <p>
 * L'estrazione testuale {@link #extractTextFromPages(PDDocument, int, int)} usa {@link PDFTextStripper} sulle stesse
 * pagine 1-based, per affiancare le immagini inviate al modello.
 */
public class PdfVisionService {

    /** DPI di default per preservare testo piccolo negli indici (circa 300 dpi tipografici). */
    public static final float DEFAULT_RENDER_DPI = 300f;

    /** Spazio verticale tra pagine quando si uniscono in un'unica immagine (per modelli a singola immagine). */
    private static final int STACKED_PAGE_GAP_PX = 8;

    private final float renderDpi;

    public PdfVisionService() {
        this(DEFAULT_RENDER_DPI);
    }

    public PdfVisionService(float renderDpi) {
        if (renderDpi <= 0) {
            throw new IllegalArgumentException("renderDpi must be positive");
        }
        this.renderDpi = renderDpi;
    }

    /**
     * Converte un intervallo di pagine in immagini {@link Image} per LangChain4j (base64 PNG).
     *
     * @param document  documento PDF aperto; non viene chiuso da questo metodo
     * @param startPage prima pagina inclusiva, <strong>1-based</strong> (come numero pagina utente)
     * @param endPage   ultima pagina inclusiva, 1-based
     * @return una voce per pagina, nello stesso ordine [{@code startPage} … {@code endPage}]
     * @throws IOException              se il rendering PDF o la codifica PNG falliscono
     * @throws IllegalArgumentException se l'intervallo è vuoto o fuori dal documento
     */
    public List<Image> renderPagesAsLangChainImages(PDDocument document, int startPage, int endPage)
            throws IOException {
        List<BufferedImage> slices = renderPagesAsBufferedImages(document, startPage, endPage);
        List<Image> images = new ArrayList<>(slices.size());
        for (BufferedImage bufferedImage : slices) {
            byte[] pngBytes = encodePng(bufferedImage);
            String base64 = Base64.getEncoder().encodeToString(pngBytes);
            images.add(Image.builder().base64Data(base64).mimeType("image/png").build());
        }
        return images;
    }

    /**
     * Estrae il testo grezzo dalle pagine indicate tramite {@link PDFTextStripper} (stesso intervallo 1-based del rendering).
     *
     * @param pdfFile   file PDF; viene aperto in lettura e chiuso al termine
     * @param startPage prima pagina inclusiva, 1-based
     * @param endPage   ultima pagina inclusiva, 1-based
     * @return testo concatenato delle pagine (può essere vuoto se non c'è testo selezionabile)
     */
    public String extractTextFromPages(File pdfFile, int startPage, int endPage) throws IOException {
        Objects.requireNonNull(pdfFile, "pdfFile");
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            return extractTextFromPages(doc, startPage, endPage);
        }
    }

    /**
     * Estrae il testo grezzo dalle pagine indicate; il documento non viene chiuso.
     *
     * @param document  PDF aperto
     * @param startPage prima pagina inclusiva, 1-based
     * @param endPage   ultima pagina inclusiva, 1-based
     * @return testo concatenato (può essere vuoto)
     */
    public String extractTextFromPages(PDDocument document, int startPage, int endPage) throws IOException {
        validatePageRange(document, startPage, endPage);
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(startPage);
        stripper.setEndPage(endPage);
        stripper.setSortByPosition(true);
        return stripper.getText(document);
    }

    /**
     * Unisce le pagine dell'indice in un'unica immagine verticale (ordine dall'alto verso il basso).
     * Alcuni modelli (es. Ollama {@code llama3.2-vision}) accettano una sola immagine per richiesta.
     */
    public Image renderIndexPagesAsSingleStackedLangChainImage(PDDocument document, int startPage, int endPage)
            throws IOException {
        List<BufferedImage> slices = renderPagesAsBufferedImages(document, startPage, endPage);
        if (slices.size() == 1) {
            byte[] pngBytes = encodePng(slices.get(0));
            String base64 = Base64.getEncoder().encodeToString(pngBytes);
            return Image.builder().base64Data(base64).mimeType("image/png").build();
        }
        int maxW = 0;
        int totalH = 0;
        for (BufferedImage slice : slices) {
            maxW = Math.max(maxW, slice.getWidth());
            totalH += slice.getHeight();
        }
        totalH += STACKED_PAGE_GAP_PX * (slices.size() - 1);
        BufferedImage combined = new BufferedImage(maxW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combined.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, maxW, totalH);
            int y = 0;
            for (int i = 0; i < slices.size(); i++) {
                BufferedImage slice = slices.get(i);
                g.drawImage(slice, 0, y, null);
                y += slice.getHeight();
                if (i < slices.size() - 1) {
                    y += STACKED_PAGE_GAP_PX;
                }
            }
        } finally {
            g.dispose();
        }
        byte[] pngBytes = encodePng(combined);
        String base64 = Base64.getEncoder().encodeToString(pngBytes);
        return Image.builder().base64Data(base64).mimeType("image/png").build();
    }

    private static void validatePageRange(PDDocument document, int startPage, int endPage) {
        Objects.requireNonNull(document, "document");
        int pageCount = document.getNumberOfPages();
        if (pageCount == 0) {
            throw new IllegalArgumentException("document has no pages");
        }
        if (startPage < 1 || endPage < startPage) {
            throw new IllegalArgumentException(
                    "startPage must be >= 1 and endPage must be >= startPage; got startPage="
                            + startPage
                            + ", endPage="
                            + endPage);
        }
        if (endPage > pageCount) {
            throw new IllegalArgumentException(
                    "endPage (" + endPage + ") exceeds page count (" + pageCount + ")");
        }
    }

    private List<BufferedImage> renderPagesAsBufferedImages(PDDocument document, int startPage, int endPage)
            throws IOException {
        validatePageRange(document, startPage, endPage);

        PDFRenderer pdfRenderer = new PDFRenderer(document);
        List<BufferedImage> slices = new ArrayList<>(endPage - startPage + 1);
        for (int pageNum = startPage; pageNum <= endPage; pageNum++) {
            int pageIndex = pageNum - 1;
            slices.add(pdfRenderer.renderImageWithDPI(pageIndex, renderDpi, ImageType.RGB));
        }
        return slices;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "png", buffer);
        if (!written) {
            throw new IOException("No PNG ImageWriter available or encoding failed");
        }
        return buffer.toByteArray();
    }
}
