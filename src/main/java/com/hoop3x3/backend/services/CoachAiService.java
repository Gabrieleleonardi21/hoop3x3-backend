package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.CoachChatRequestDTO;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.exceptions.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

import static com.hoop3x3.backend.services.LogSupport.perLog;

/**
 * Proxy verso Groq per il Coach AI: la chiave resta sul server e non arriva mai al browser.
 * Il corpo (messages + tools nel formato OpenAI) è inoltrato dopo un controllo di forma e dimensione;
 * modello e limite di token li fissa il server.
 */
@Service
@EnableConfigurationProperties(GroqProperties.class)
public class CoachAiService {

    private static final Logger log = LoggerFactory.getLogger(CoachAiService.class);

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final Duration TIMEOUT_CONNESSIONE = Duration.ofSeconds(5);
    private static final Duration TIMEOUT_RISPOSTA = Duration.ofSeconds(60);
    /* Limiti della richiesta: bastano largamente al Coach dell'app e impediscono l'uso come proxy generico */
    private static final int MAX_MESSAGGI = 60;
    private static final int MAX_CARATTERI_MESSAGGI = 100_000;
    private static final int MAX_TOOL = 20;
    private static final int MAX_CARATTERI_TOOL = 50_000;
    private static final String SYSTEM = "system";
    /** I ruoli dei messaggi dopo il primo: `system` può essere solo il primo, il client non lo infila in mezzo alla conversazione */
    private static final Set<String> RUOLI_DOPO_IL_PRIMO = Set.of("user", "assistant", "tool");
    private static final Set<String> RUOLI = Set.of(SYSTEM, "user", "assistant", "tool");
    /**
     * Gli strumenti che il Coach può chiedere al modello: i nomi di COACH_TOOLS in Hoops-3x3/src/coach/toolDefs.ts (frontend),
     * uno per funzione di toolHandlers.ts. Un tool con un altro nome, o di un altro tipo, non passa: con `type` diverso da
     * «function» Groq attiva i suoi strumenti integrati (browser_search, code_interpreter), pagati con la chiave del server.
     * Uno strumento nuovo nel frontend va aggiunto anche qui
     */
    static final Set<String> TOOL_AMMESSI = Set.of("crea_lega", "crea_tappa", "annulla_risultato", "aggiorna_squadra",
            "registra_squadra", "registra_giocatore", "sorteggia_gironi", "genera_fasi_dirette", "registra_risultato",
            "concludi_tappa");

    // Chiave, modello e reasoning effort da groq.* (GroqProperties)
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;
    private final ObjectMapper mapper;
    private final String url;
    private final RestClient http;

    @Autowired // con due costruttori Spring deve sapere quale usare
    public CoachAiService(ObjectMapper mapper, GroqProperties proprieta) {
        this(mapper, proprieta, GROQ_URL, TIMEOUT_CONNESSIONE, TIMEOUT_RISPOSTA);
    }

    /** Per i test: Groq finto su un indirizzo locale e timeout brevi, per non aspettare 60 secondi */
    CoachAiService(ObjectMapper mapper, GroqProperties proprieta, String url, Duration timeoutConnessione, Duration timeoutRisposta) {
        this.mapper = mapper;
        this.apiKey = proprieta.api().key();
        this.model = proprieta.model();
        this.reasoningEffort = proprieta.reasoningEffort();
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
            log.warn("Richiesta al Coach AI ma groq.api.key non è configurata sul server: risposta 503");
            throw new UpstreamException(HttpStatus.SERVICE_UNAVAILABLE, "Coach AI non configurato sul server (groq.api.key mancante)");
        }
        valida(req);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        // gpt-oss ragiona prima di rispondere e i token di reasoning contano nel budget:
        // effort basso + budget più ampio evitano risposte troncate (content vuoto)
        body.put("max_tokens", 1200);
        // Solo se configurato: un modello che non accetta il campo risponderebbe 400 a ogni richiesta (groq.reasoning-effort vuoto)
        if (!reasoningEffort.isBlank()) body.put("reasoning_effort", reasoningEffort);
        body.set("messages", req.messages());
        if (req.tools() != null && !req.tools().isEmpty()) {
            body.set("tools", req.tools());
            body.put("tool_choice", "auto");
        }
        String risposta;
        try {
            risposta = http.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // Stato e corpo di Groq restano nei log del server; al client va un messaggio nostro. Il corpo può riportare parti
            // della richiesta dell'utente (e di solito sta su più righe): passa da perLog, così resta su una riga sola
            int stato = e.getStatusCode().value();
            log.warn("Groq ha risposto {}: {}", stato, perLog(tronca(e.getResponseBodyAsString())));
            if (stato == 429) throw new UpstreamException(HttpStatus.TOO_MANY_REQUESTS, "Limite richieste Coach AI raggiunto, riprova tra poco");
            if (stato == 400 || stato == 413 || stato == 422) throw new BadRequestException("Richiesta al Coach AI non valida o troppo lunga");
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, "Il servizio AI non è disponibile in questo momento");
        } catch (RestClientException e) {
            // Rete, timeout o risposta interrotta a metà: la causa resta nell'eccezione, che va nel log con il suo stack
            log.warn("Groq non raggiungibile o risposta interrotta", e);
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, "Servizio AI non raggiungibile");
        }
        return leggiRisposta(risposta);
    }

    /** La risposta di Groq è un oggetto JSON: altro (pagina HTML, corpo vuoto o a metà) è un guasto a monte, con il corpo nei log */
    private JsonNode leggiRisposta(String corpo) {
        try {
            JsonNode json = mapper.readTree(corpo);
            if (json.isObject()) return json;
        } catch (JacksonException | IllegalArgumentException _) {
            // Non è JSON (o il corpo manca: readTree(null) lancia IllegalArgumentException): stessa risposta di un JSON non oggetto
        }
        log.warn("Risposta di Groq non valida, non è un oggetto JSON: {}", perLog(tronca(corpo)));
        throw new UpstreamException(HttpStatus.BAD_GATEWAY, "Il servizio AI ha dato una risposta non valida");
    }

    /** Forma e dimensione di messages/tools: il proxy serve solo al Coach dell'app */
    private void valida(CoachChatRequestDTO req) {
        JsonNode messaggi = req.messages();
        if (!messaggi.isArray() || messaggi.isEmpty() || messaggi.size() > MAX_MESSAGGI) {
            throw new BadRequestException("messages deve essere un elenco da 1 a " + MAX_MESSAGGI + " messaggi");
        }
        for (int i = 0; i < messaggi.size(); i++) {
            JsonNode m = messaggi.get(i);
            if (!m.isObject() || !RUOLI.contains(m.path("role").asString(""))) {
                throw new BadRequestException("Ogni messaggio deve avere un ruolo valido");
            }
            // Le istruzioni al modello (system) le scrive il Coach dell'app, in testa: un secondo system, o uno in mezzo alla
            // conversazione, è un client che prova a riscriverle
            if (i > 0 && !RUOLI_DOPO_IL_PRIMO.contains(m.path("role").asString())) {
                throw new BadRequestException("Solo il primo messaggio può avere il ruolo system");
            }
        }
        if (messaggi.toString().length() > MAX_CARATTERI_MESSAGGI) {
            throw new BadRequestException("Conversazione troppo lunga: cancella la chat e riprova");
        }
        JsonNode tool = req.tools();
        if (tool == null || tool.isNull()) return;
        if (!tool.isArray() || tool.size() > MAX_TOOL || tool.toString().length() > MAX_CARATTERI_TOOL) {
            throw new BadRequestException("tools deve essere un elenco di al massimo " + MAX_TOOL + " strumenti");
        }
        for (JsonNode t : tool) {
            if (!t.isObject() || !"function".equals(t.path("type").asString(""))
                    || !TOOL_AMMESSI.contains(t.path("function").path("name").asString(""))) {
                throw new BadRequestException("Ogni strumento deve essere una funzione del Coach (type function e un nome tra quelli dell'app)");
            }
        }
    }

    /** Nei log il corpo di Groq entra al massimo con 500 caratteri */
    private static String tronca(String s) {
        if (s == null) return "";
        if (s.length() <= 500) return s;
        return s.substring(0, 500) + "…";
    }
}
