package it.flavianopetrocchi.jpdfbookmarks.ai.service;

/**
 * Errore nel flusso di preparazione immagini o di estrazione segnalibri tramite IA.
 * Messaggi pensati per essere mostrati direttamente all'utente ({@link #getMessage()}).
 */
public class AiOrchestrationException extends Exception {

    public AiOrchestrationException(String message) {
        super(message);
    }

    public AiOrchestrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
