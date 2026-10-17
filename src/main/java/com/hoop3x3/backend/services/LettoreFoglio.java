package com.hoop3x3.backend.services;

import tools.jackson.databind.JsonNode;

/**
 * Chi legge la foto di un foglio di tappa e la trascrive nella bozza di SchemaFoglio: un modello con immagini di Groq o di
 * Anthropic (ai.lettore-foglio). Prompt, schema e controlli sono gli stessi; cambia solo il formato della chiamata.
 * I messaggi d'errore sono un contratto con il frontend (README, «Lettura dei fogli (foto)»).
 */
interface LettoreFoglio {

    String FORNITORE_NON_DISPONIBILE = "Il lettore dei fogli non è disponibile in questo momento";
    String FORNITORE_NON_RAGGIUNGIBILE = "Lettore dei fogli non raggiungibile";
    String RISPOSTA_NON_VALIDA = "Il lettore dei fogli ha dato una risposta non valida";
    String LIMITE_FORNITORE = "Limite del lettore dei fogli raggiunto, riprova tra poco";

    /** Il fornitore ha la sua chiave: senza, la lettura è spenta */
    boolean isConfigurato();

    /**
     * La bozza letta, così come l'ha scritta il modello: un oggetto con squadre, partite e dubbi elenchi (SchemaFoglio.haLaForma).
     * La ripulisce FoglioService. Un errore del fornitore è un UpstreamException con lo stato per il client
     */
    JsonNode leggi(ImmagineFoglio immagine);
}
