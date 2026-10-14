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
 * Lettura del foglio con Anthropic (Messages API): la foto e il prompt in un messaggio, la bozza nell'input di uno strumento
 * forzato (tool_choice), che obbliga il modello a rispondere nello schema. Haiku 5.5 accetta lo strumento forzato; un modello
 * che non lo accetta risponde 400, e il client riceve il 502 della configurazione.
 */
@Service
@EnableConfigurationProperties(AnthropicProperties.class)
public class LettoreFoglioAnthropic implements LettoreFoglio {

    private static final Logger log = LoggerFactory.getLogger(LettoreFoglioAnthropic.class);

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String VERSIONE_API = "2023-06-01";
    private static final Duration TIMEOUT_CONNESSIONE = Duration.ofSeconds(5);
    private static final Duration TIMEOUT_RISPOSTA = Duration.ofSeconds(60);

    private final ObjectMapper mapper;
    private final String apiKey;
    private final String modello;
    private final JsonNode schema;
    private final ChiamataFornitore chiamata;

    @Autowired // con due costruttori Spring deve sapere quale usare
    public LettoreFoglioAnthropic(ObjectMapper mapper, AnthropicProperties proprieta) {
        this(mapper, proprieta, ANTHROPIC_URL, TIMEOUT_CONNESSIONE, TIMEOUT_RISPOSTA);
    }

    /** Per i test: Anthropic finto su un indirizzo locale e timeout brevi */
    LettoreFoglioAnthropic(ObjectMapper mapper, AnthropicProperties proprieta, String url, Duration timeoutConnessione,
                           Duration timeoutRisposta) {
        this.mapper = mapper;
        this.apiKey = proprieta.api().key();
        this.modello = proprieta.model();
        this.schema = mapper.readTree(SchemaFoglio.SCHEMA);
        this.chiamata = new ChiamataFornitore("Anthropic", log, mapper, url, timeoutConnessione, timeoutRisposta);
    }

    @Override
    public boolean isConfigurato() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public JsonNode leggi(ImmagineFoglio immagine) {
        ObjectNode corpo = mapper.createObjectNode();
        corpo.put("model", modello);
        corpo.put("max_tokens", 4000);
        // L'immagine prima del testo, come consiglia Anthropic per le domande su un'immagine
        ArrayNode parti = mapper.createArrayNode();
        parti.addObject().put("type", "image").putObject("source")
                .put("type", "base64").put("media_type", immagine.mime()).put("data", immagine.base64());
        parti.addObject().put("type", "text").put("text", SchemaFoglio.PROMPT);
        corpo.putArray("messages").addObject().put("role", "user").set("content", parti);
        corpo.putArray("tools").addObject().put("name", SchemaFoglio.NOME_STRUMENTO)
                .put("description", "La trascrizione del foglio").set("input_schema", schema);
        corpo.putObject("tool_choice").put("type", "tool").put("name", SchemaFoglio.NOME_STRUMENTO);

        JsonNode risposta = chiamata.invia(intestazioni -> {
            intestazioni.set("x-api-key", apiKey);
            intestazioni.set("anthropic-version", VERSIONE_API);
        }, corpo);
        // Il blocco si cerca per tipo e nome, non per posizione: prima può esserci un blocco di testo o di ragionamento
        for (JsonNode blocco : risposta.path("content")) {
            if ("tool_use".equals(blocco.path("type").asString(""))
                    && SchemaFoglio.NOME_STRUMENTO.equals(blocco.path("name").asString(""))) {
                return chiamata.bozza(blocco.path("input"), risposta);
            }
        }
        throw chiamata.nonValida(risposta.toString(), risposta);
    }
}
