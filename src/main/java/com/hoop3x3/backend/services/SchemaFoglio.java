package com.hoop3x3.backend.services;

/**
 * Le istruzioni al modello e la forma della bozza, uguali per Groq e Anthropic: cambia solo il formato della chiamata. Sono
 * quelle della prova OCR (piano-miglioramento/prototipi/ocr-foglio, prompt.txt e schema.json): chi le ritocca qui le ritocca
 * anche lì, così la prova con le foto vere misura quello che gira sul server.
 */
final class SchemaFoglio {

    /** Il nome dello schema per Groq (response_format) e dello strumento forzato per Anthropic */
    static final String NOME_STRUMENTO = "foglio_tappa";

    /** Il testo è una riga sola (le barre a fine riga la continuano): lo stesso testo di prompt.txt */
    static final String PROMPT = """
            Leggi questo foglio di una tappa di basket 3x3 scritto a mano e trascrivilo nello schema. Regole: copia i nomi \
            come sono scritti; non inventare squadre, giocatori o punteggi; per ogni cosa che non leggi con certezza metti \
            null (o lascia fuori la riga) e aggiungi una voce in `dubbi` che dica dove e che cosa non si legge; le partite \
            sono nell'ordine del foglio; i punteggi sono numeri interi.\
            """;

    /**
     * Schema chiuso: ogni oggetto ha additionalProperties false e tutti i campi in required, come chiede lo strict di Groq
     * (un campo che manca sul foglio si scrive null, non si toglie)
     */
    static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["nome", "luogo", "data", "squadre", "gironi", "partite", "dubbi"],
              "properties": {
                "nome": { "type": ["string", "null"], "description": "Nome della tappa" },
                "luogo": { "type": ["string", "null"] },
                "data": { "type": ["string", "null"], "description": "Data della tappa, aaaa-mm-gg" },
                "squadre": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["nome", "giocatori"],
                    "properties": {
                      "nome": { "type": "string" },
                      "giocatori": { "type": "array", "items": { "type": "string" } }
                    }
                  }
                },
                "gironi": {
                  "type": ["array", "null"],
                  "description": "I nomi delle squadre di ogni girone, se il foglio li ha",
                  "items": { "type": "array", "items": { "type": "string" } }
                },
                "partite": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["a", "b", "puntiA", "puntiB"],
                    "properties": {
                      "a": { "type": "string", "description": "Nome della prima squadra" },
                      "b": { "type": "string", "description": "Nome della seconda squadra" },
                      "puntiA": { "type": ["integer", "null"] },
                      "puntiB": { "type": ["integer", "null"] }
                    }
                  }
                },
                "dubbi": {
                  "type": "array",
                  "description": "Dove e che cosa non si legge con certezza",
                  "items": { "type": "string" }
                }
              }
            }
            """;

    private SchemaFoglio() {}
}
