package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.CoachChatRequestDTO;
import com.hoop3x3.backend.exceptions.UpstreamException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Proxy verso Groq per il Coach AI: la chiave resta sul server e non arriva mai al browser.
 * Il corpo (messages + tools nel formato OpenAI) è inoltrato così com'è; modello e limite
 * di token li fissa il server.
 */
@Service
public class CoachAiService {

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final Duration TIMEOUT_CONNESSIONE = Duration.ofSeconds(5);
    private static final Duration TIMEOUT_RISPOSTA = Duration.ofSeconds(60);

    @Value("${groq.api.key:}")
    private String apiKey;

    // openai/gpt-oss-120b: gratuito su Groq, supporta il tool calling (llama-3.3-70b è stato dismesso)
    @Value("${groq.model:openai/gpt-oss-120b}")
    private String model;

    private final ObjectMapper mapper;
    private final String url;
    private final RestClient http;

    @Autowired // con due costruttori Spring deve sapere quale usare
    public CoachAiService(ObjectMapper mapper) {
        this(mapper, GROQ_URL, TIMEOUT_CONNESSIONE, TIMEOUT_RISPOSTA);
    }

    /** Per i test: Groq finto su un indirizzo locale e timeout brevi, per non aspettare 60 secondi */
    CoachAiService(ObjectMapper mapper, String url, Duration timeoutConnessione, Duration timeoutRisposta) {
        this.mapper = mapper;
        this.url = url;
        // Senza timeout una risposta lenta di Groq terrebbe occupato un thread del server a tempo indeterminato
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeoutConnessione).build());
        factory.setReadTimeout(timeoutRisposta);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigurato() {
        return apiKey != null && !apiKey.isBlank();
    }

    public JsonNode chat(CoachChatRequestDTO req) {
        if (!isConfigurato()) {
            throw new UpstreamException(HttpStatus.SERVICE_UNAVAILABLE, "Coach AI non configurato sul server (groq.api.key mancante)");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        // gpt-oss ragiona prima di rispondere e i token di reasoning contano nel budget:
        // effort basso + budget più ampio evitano risposte troncate (content vuoto)
        body.put("max_tokens", 1200);
        body.put("reasoning_effort", "low");
        body.set("messages", req.messages());
        if (req.tools() != null && req.tools().isArray() && !req.tools().isEmpty()) {
            body.set("tools", req.tools());
            body.put("tool_choice", "auto");
        }
        try {
            String risposta = http.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            return mapper.readTree(risposta);
        } catch (RestClientResponseException e) {
            // Gli errori di Groq vengono tradotti in status nostri: il client non deve distinguere JWT da chiave Groq
            if (e.getStatusCode().value() == 429) throw new UpstreamException(HttpStatus.TOO_MANY_REQUESTS, "Limite richieste Coach AI raggiunto, riprova tra poco");
            if (e.getStatusCode().value() == 401) throw new UpstreamException(HttpStatus.BAD_GATEWAY, "Chiave Groq non valida sul server");
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, "Errore del servizio AI: " + e.getStatusCode().value());
        } catch (ResourceAccessException _) {
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, "Servizio AI non raggiungibile");
        }
    }
}
