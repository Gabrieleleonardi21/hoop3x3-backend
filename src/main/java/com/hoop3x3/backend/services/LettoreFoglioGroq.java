package com.hoop3x3.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * Lettura del foglio con Groq (formato OpenAI): il prompt e la foto in un messaggio, la bozza nel content della risposta.
 * response_format con lo schema e strict: true fa scrivere al modello solo JSON in quella forma. La chiave è quella del
 * Coach (groq.api.key), il modello è quello con immagini (groq.modello-visione).
 */
@Service
@EnableConfigurationProperties(GroqProperties.class)
public class LettoreFoglioGroq implements LettoreFoglio {

    private static final Logger log = LoggerFactory.getLogger(LettoreFoglioGroq.class);

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final Duration TIMEOUT_CONNESSIONE = Duration.ofSeconds(5);
    private static final Duration TIMEOUT_RISPOSTA = Duration.ofSeconds(60);

    private final ObjectMapper mapper;
    private final String apiKey;
    private final String modello;
    private final JsonNode schema;
    private final ChiamataFornitore chiamata;

    @Autowired // con due costruttori Spring deve sapere quale usare
    public LettoreFoglioGroq(ObjectMapper mapper, GroqProperties proprieta) {
        this(mapper, proprieta, GROQ_URL, TIMEOUT_CONNESSIONE, TIMEOUT_RISPOSTA);
    }

    /** Per i test: Groq finto su un indirizzo locale e timeout brevi */
    LettoreFoglioGroq(ObjectMapper mapper, GroqProperties proprieta, String url, Duration timeoutConnessione,
                      Duration timeoutRisposta) {
        this.mapper = mapper;
        this.apiKey = proprieta.api().key();
        this.modello = proprieta.modelloVisione();
        this.schema = mapper.readTree(SchemaFoglio.SCHEMA);
        this.chiamata = new ChiamataFornitore("Groq", log, mapper, url, timeoutConnessione, timeoutRisposta);
    }

    @Override
    public boolean isConfigurato() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public JsonNode leggi(ImmagineFoglio immagine) {
        ObjectNode corpo = mapper.createObjectNode();
        corpo.put("model", modello);
        // Una trascrizione, non un testo creativo: temperatura 0. Niente reasoning_effort né tools: il modello con immagini
        // potrebbe non accettarli, e la risposta è solo la bozza
        corpo.put("temperature", 0);
        corpo.put("max_tokens", 4000);
        ArrayNode parti = mapper.createArrayNode();
        parti.addObject().put("type", "text").put("text", SchemaFoglio.PROMPT);
        parti.addObject().put("type", "image_url").putObject("image_url")
                .put("url", "data:" + immagine.mime() + ";base64," + immagine.base64());
        corpo.putArray("messages").addObject().put("role", "user").set("content", parti);
        corpo.putObject("response_format").put("type", "json_schema").putObject("json_schema")
                .put("name", SchemaFoglio.NOME_STRUMENTO).put("strict", true).set("schema", schema);

        JsonNode risposta = chiamata.invia(intestazioni -> intestazioni.setBearerAuth(apiKey), corpo);
        JsonNode content = risposta.at("/choices/0/message/content");
        if (!content.isString()) throw chiamata.nonValida(risposta.toString());
        return chiamata.bozza(content.asString());
    }
}
