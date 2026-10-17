package com.hoop3x3.backend.services;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * L'adattatore del Coach per Anthropic (Messages API). Il frontend parla sempre nel formato OpenAI di Groq (aiService.ts):
 * la richiesta si traduce per Anthropic e la risposta torna nella forma choices[0].message che il frontend legge. Controlli,
 * limiti e regola del contesto restano in CoachAiService, uguali per i due fornitori.
 */
final class CoachAnthropic {

    static final String VERSIONE_API = "2023-06-01";
    /** Per Anthropic il primo messaggio dev'essere dell'utente: questo va in testa quando la chat comincia con il Coach */
    static final String INIZIO = "(inizio della conversazione)";

    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    private static final String TEXT = "text";

    private CoachAnthropic() {
    }

    /**
     * Il corpo per Anthropic. `messaggi` sono quelli di AmbitoCoach.conRegola: il primo è il system con la regola, che va nel
     * campo `system`. `strumenti` è null quando il client non ne manda
     */
    static ObjectNode richiesta(ObjectMapper mapper, String modello, int maxTokens, ArrayNode messaggi, JsonNode strumenti) {
        ObjectNode corpo = mapper.createObjectNode();
        corpo.put("model", modello);
        corpo.put("max_tokens", maxTokens);
        // Haiku 5.5 pensa di base, e i blocchi di pensiero andrebbero rimandati con i risultati degli strumenti: il frontend
        // tiene solo testo e tool_calls, quindi il pensiero si spegne (Haiku lo accetta fino all'effort high; il predefinito è medium)
        corpo.putObject("thinking").put("type", "disabled");
        boolean conStrumenti = strumenti != null;
        ArrayNode tradotti = corpo.putArray("messages");
        for (JsonNode m : messaggi) {
            String ruolo = m.path("role").asString("");
            if (ruolo.equals("system")) {
                corpo.put("system", m.path("content").asString(""));
            } else if (ruolo.equals("tool")) {
                aggiungi(tradotti, USER, risultato(mapper, m, conStrumenti));
            } else {
                aggiungi(tradotti, ruolo, blocchi(mapper, m, conStrumenti));
            }
        }
        if (conStrumenti) corpo.set("tools", strumenti(mapper, strumenti));
        return corpo;
    }

    /**
     * La risposta di Anthropic nella forma OpenAI: content è il testo dei blocchi text (null se non ce n'è), tool_calls i blocchi
     * tool_use con gli argomenti come testo JSON. I blocchi di pensiero si saltano. Null se non è una risposta della Messages API
     */
    static ObjectNode risposta(ObjectMapper mapper, JsonNode anthropic) {
        JsonNode blocchi = anthropic.path("content");
        if (!blocchi.isArray()) return null;
        StringBuilder testo = new StringBuilder();
        ArrayNode chiamate = mapper.createArrayNode();
        for (JsonNode b : blocchi) {
            String tipo = b.path("type").asString("");
            if (tipo.equals(TEXT)) testo.append(b.path(TEXT).asString(""));
            if (tipo.equals("tool_use")) {
                String argomenti = "{}";
                if (b.path("input").isObject()) argomenti = b.path("input").toString();
                ObjectNode chiamata = chiamate.addObject().put("id", b.path("id").asString("")).put("type", "function");
                chiamata.putObject("function").put("name", b.path("name").asString("")).put("arguments", argomenti);
            }
        }
        ObjectNode openai = mapper.createObjectNode();
        ObjectNode scelta = openai.putArray("choices").addObject();
        ObjectNode messaggio = scelta.putObject("message").put("role", ASSISTANT);
        if (testo.isEmpty()) messaggio.putNull("content");
        else messaggio.put("content", testo.toString());
        if (chiamate.isEmpty()) {
            scelta.put("finish_reason", "stop");
        } else {
            messaggio.set("tool_calls", chiamate);
            scelta.put("finish_reason", "tool_calls");
        }
        return openai;
    }

    /**
     * Accoda i blocchi di un messaggio. Due ruoli uguali di fila diventano un messaggio solo: così i risultati degli strumenti
     * di un giro stanno in un unico messaggio user, come vuole Anthropic. Un messaggio senza blocchi (testo vuoto) si salta
     */
    private static void aggiungi(ArrayNode messaggi, String ruolo, ArrayNode blocchi) {
        if (blocchi.isEmpty()) return;
        if (messaggi.isEmpty() && ruolo.equals(ASSISTANT)) {
            messaggi.addObject().put("role", USER).putArray("content").addObject().put("type", TEXT).put(TEXT, INIZIO);
        }
        JsonNode ultimo = messaggi.path(messaggi.size() - 1);
        if (ruolo.equals(ultimo.path("role").asString(""))) {
            ((ArrayNode) ultimo.path("content")).addAll(blocchi);
            return;
        }
        messaggi.addObject().put("role", ruolo).set("content", blocchi);
    }

    /** Testo e chiamate di strumenti di un messaggio user o assistant; senza strumenti nella richiesta le chiamate diventano testo */
    private static ArrayNode blocchi(ObjectMapper mapper, JsonNode m, boolean conStrumenti) {
        ArrayNode blocchi = mapper.createArrayNode();
        testo(blocchi, m.path("content"));
        for (JsonNode chiamata : m.path("tool_calls")) {
            String nome = chiamata.path("function").path("name").asString("");
            String argomenti = chiamata.path("function").path("arguments").asString("");
            if (conStrumenti) {
                blocchi.addObject().put("type", "tool_use").put("id", chiamata.path("id").asString("")).put("name", nome)
                        .set("input", oggetto(mapper, argomenti));
            } else {
                // Anthropic rifiuta tool_use e tool_result in una richiesta senza tools (la chiusura di askCoachWithTools)
                testo(blocchi, "Strumento " + nome + " chiamato con " + argomenti);
            }
        }
        return blocchi;
    }

    /** Il messaggio `tool`: un tool_result legato alla chiamata, o il testo del risultato se la richiesta non ha strumenti */
    private static ArrayNode risultato(ObjectMapper mapper, JsonNode m, boolean conStrumenti) {
        ArrayNode blocchi = mapper.createArrayNode();
        String contenuto = m.path("content").asString("");
        if (conStrumenti) {
            blocchi.addObject().put("type", "tool_result").put("tool_use_id", m.path("tool_call_id").asString(""))
                    .put("content", contenuto);
        } else {
            testo(blocchi, "Risultato dello strumento: " + contenuto);
        }
        return blocchi;
    }

    /** Il content OpenAI (un testo, o un elenco di parti di tipo text) come blocchi di testo, senza quelli vuoti che Anthropic rifiuta */
    private static void testo(ArrayNode blocchi, JsonNode content) {
        if (content.isString()) testo(blocchi, content.stringValue());
        if (!content.isArray()) return;
        for (JsonNode parte : content) {
            if (TEXT.equals(parte.path("type").asString(""))) testo(blocchi, parte.path(TEXT).asString(""));
        }
    }

    private static void testo(ArrayNode blocchi, String testo) {
        if (!testo.isBlank()) blocchi.addObject().put("type", TEXT).put(TEXT, testo);
    }

    /** Gli argomenti (testo JSON) come oggetto, l'input di tool_use; {} se non lo sono (un oggetto è obbligatorio per Anthropic) */
    private static ObjectNode oggetto(ObjectMapper mapper, String argomenti) {
        try {
            JsonNode letto = mapper.readTree(argomenti);
            if (letto != null && letto.isObject()) return (ObjectNode) letto;
        } catch (JacksonException _) {
            // Argomenti non JSON: un oggetto vuoto, come per un testo che non è un oggetto
        }
        return mapper.createObjectNode();
    }

    /** Gli strumenti OpenAI ({type:function, function:{name, description, parameters}}) come {name, description, input_schema} */
    private static ArrayNode strumenti(ObjectMapper mapper, JsonNode strumenti) {
        ArrayNode tradotti = mapper.createArrayNode();
        for (JsonNode s : strumenti) {
            JsonNode funzione = s.path("function");
            ObjectNode tradotto = tradotti.addObject().put("name", funzione.path("name").asString(""));
            if (funzione.path("description").isString()) tradotto.put("description", funzione.path("description").stringValue());
            JsonNode parametri = funzione.path("parameters");
            if (parametri.isObject()) tradotto.set("input_schema", parametri);
            else tradotto.putObject("input_schema").put("type", "object");
        }
        return tradotti;
    }
}
