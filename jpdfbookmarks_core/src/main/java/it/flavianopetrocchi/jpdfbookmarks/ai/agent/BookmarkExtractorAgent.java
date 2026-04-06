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
                    + "- Estrai i numeri di pagina esattamente come scritti (l'offset verrà applicato dal codice Java dopo).\n"
                    + "- Non inventare numeri; se una voce non ha numero, usa \"page_number\": -1.\n"
                    + "- Con più immagini o pagine in sequenza, un solo indice continuo; non duplicare voci già chiuse sopra.\n"
                    + "- Titoli nella lingua originale del documento.\n"
                    + "\n"
                    + "Restituisci SOLO il JSON: un array (primo carattere '[', ultimo ']') di oggetti con \"title\", "
                    + "\"page_number\", \"children\" (array vuoto se foglia). Nessun markdown, nessun testo fuori dal JSON.")
    @UserMessage(
            "Testo digitale (text layer) estratto dalle stesse pagine (può essere vuoto o imperfetto):\n---\n"
                    + "{{textLayerContent}}\n---\n"
                    + "Immagini delle pagine d'indice in ordine. Solo l'array JSON (title, page_number, children), nient'altro.")
    String extractBookmarks(
            @V("textLayerContent") String textLayerContent, @UserMessage List<ImageContent> pages);
}
