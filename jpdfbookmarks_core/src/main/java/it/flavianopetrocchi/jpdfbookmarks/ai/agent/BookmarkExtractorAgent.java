/*
 * BookmarkExtractorAgent.java
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

package it.flavianopetrocchi.jpdfbookmarks.ai.agent;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import java.util.List;

/**
 * Estrae una gerarchia di segnalibri da immagini di indice/sommario e testo digitale estratto (PDFBox), tramite modello multimodale.
 * Registrabile come bean Spring con {@code langchain4j-spring-boot-starter}, oppure creabile con
 * {@code AiServices.builder(BookmarkExtractorAgent.class)...build()}.
 */
@AiService
public interface BookmarkExtractorAgent {

    @SystemMessage(
            "Agisci come un esperto di trascrizione documenti. Hai a disposizione due fonti per le stesse pagine di un indice:\n"
                    + "\n"
                    + "Immagini: Utili per capire i rientri (indentazione), il grassetto e la struttura gerarchica.\n"
                    + "\n"
                    + "Testo Digitale: Fonte infallibile per i caratteri e i numeri di pagina.\n"
                    + "\n"
                    + "Regole operative:\n"
                    + "- Se il testo digitale e l'immagine dicono cose diverse sui numeri, fidati del testo digitale.\n"
                    + "- Se una voce nell'immagine è chiaramente rientrata rispetto a quella sopra, mettila in \"children\" "
                    + "del nodo padre nel JSON.\n"
                    + "- Se una voce ha un riferimento pagina, salva SEMPRE il testo esatto in \"page_label_raw\" (es. "
                    + "\"xii\", \"A-12\", \"40\").\n"
                    + "- Metti \"page_number\" solo se il riferimento è chiaramente un intero arabo; altrimenti usa "
                    + "\"page_number\": -1.\n"
                    + "- Con più immagini o pagine in sequenza, un solo indice continuo; non duplicare voci già chiuse sopra.\n"
                    + "- Titoli nella lingua originale del documento.\n"
                    + "\n"
                    + "Restituisci SOLO il JSON: un array (primo carattere '[', ultimo ']') di oggetti con \"title\", "
                    + "\"page_number\", \"page_label_raw\", \"children\" (array vuoto se foglia). Nessun markdown, "
                    + "nessun testo fuori dal JSON.")
    @UserMessage(
            "Testo digitale (text layer) estratto dalle stesse pagine (può essere vuoto o imperfetto):\n---\n"
                    + "{{textLayerContent}}\n---\n"
                    + "Immagini delle pagine d'indice in ordine. Solo l'array JSON (title, page_number, page_label_raw, "
                    + "children), nient'altro.")
    String extractBookmarks(
            @V("textLayerContent") String textLayerContent, @UserMessage List<ImageContent> pages);
}
