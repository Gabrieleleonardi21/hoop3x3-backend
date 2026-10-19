package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.CoachChatRequestDTO;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.exceptions.UpstreamException;
import com.hoop3x3.backend.support.Testo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import static com.hoop3x3.backend.services.LogSupport.perLog;
import static com.hoop3x3.backend.services.LogSupport.tronca;

/**
 * Proxy del Coach AI verso Groq o Anthropic (coach.fornitore): la chiave resta sul server e non arriva mai al browser.
 * Il corpo (messages + tools nel formato OpenAI) passa un controllo di forma e dimensione; poi va a Groq così com'è, o ad
 * Anthropic tradotto da CoachAnthropic, che riporta anche la risposta nel formato OpenAI: il frontend è lo stesso per i due.
 * Modello e limite di token li fissa il server. La regola del contesto (AmbitoCoach) entra nei messaggi e filtra la risposta.
 */
@Service
@EnableConfigurationProperties({CoachProperties.class, GroqProperties.class, AnthropicProperties.class})
public class CoachAiService {

    private static final Logger log = LoggerFactory.getLogger(CoachAiService.class);

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String GROQ = "groq";
    private static final String ANTHROPIC = "anthropic";
    /** Token della risposta, per i due fornitori: con gpt-oss comprendono il ragionamento */
    private static final int MAX_TOKENS = 1200;
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
     * Uno strumento nuovo nel frontend va aggiunto anche qui (correggi_squadra: nomi e giocatori di una squadra della tappa,
     * per sistemare una bozza letta da una foto; crea_tappa_simulata, simula_risultati e registra_risultati: la tappa di prova
     * e i risultati, inventati o dettati, che il frontend esegue nel browser)
     */
    static final Set<String> TOOL_AMMESSI = Set.of("crea_lega", "crea_tappa", "annulla_risultato", "aggiorna_squadra",
            "registra_squadra", "registra_giocatore", "sorteggia_gironi", "genera_fasi_dirette", "registra_risultato",
            "concludi_tappa", "correggi_squadra", "crea_tappa_simulata", "simula_risultati", "registra_risultati");

    /** «groq», «anthropic», o vuoto se coach.fornitore non è nessuno dei due (Coach spento) */
    private final String fornitore;
    /** Il nome del fornitore nei log */
    private final String nome;
    /** Perché il Coach è spento, se lo è: la proprietà da impostare, nel log e nel messaggio del 503 */
    private final String causaSpento;
    // Chiave e modello del fornitore scelto (groq.* o anthropic.*); reasoning effort solo per Groq
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;
    private final ObjectMapper mapper;
    private final String url;
    private final RestClient http;

    @Autowired // con due costruttori Spring deve sapere quale usare
    public CoachAiService(ObjectMapper mapper, CoachProperties coach, GroqProperties groq, AnthropicProperties anthropic) {
        this(mapper, coach.fornitore(), groq, anthropic, indirizzo(coach.fornitore()), TIMEOUT_CONNESSIONE, TIMEOUT_RISPOSTA);
    }

    /** Per i test: Groq o Anthropic finti su un indirizzo locale e timeout brevi, per non aspettare 60 secondi */
    CoachAiService(ObjectMapper mapper, String fornitore, GroqProperties groq, AnthropicProperties anthropic, String url,
                   Duration timeoutConnessione, Duration timeoutRisposta) {
        this.mapper = mapper;
        this.reasoningEffort = groq.reasoningEffort();
        this.url = url;
        String scelto = scelto(fornitore);
        if (scelto.equals(ANTHROPIC)) {
            this.fornitore = ANTHROPIC;
            this.nome = "Anthropic";
            this.causaSpento = "anthropic.api.key mancante";
            this.apiKey = anthropic.api().key();
            this.model = anthropic.model();
        } else if (scelto.equals(GROQ)) {
            this.fornitore = GROQ;
            this.nome = "Groq";
            this.causaSpento = "groq.api.key mancante";
            this.apiKey = groq.api().key();
            this.model = groq.model();
        } else {
            // Un nome sbagliato non ferma il server (il Coach è facoltativo), ma lo dice all'avvio
            log.warn("coach.fornitore vale «{}», né groq né anthropic: Coach AI spento", perLog(tronca(scelto)));
            this.fornitore = "";
            this.nome = "nessun fornitore";
            this.causaSpento = "coach.fornitore non valido";
            this.apiKey = "";
            this.model = "";
        }
        // Senza timeout una risposta lenta del fornitore terrebbe occupato un thread del server a tempo indeterminato
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeoutConnessione).build());
        factory.setReadTimeout(timeoutRisposta);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Il fornitore come lo scrive la configurazione, senza spazi e maiuscole; vuoto vale il predefinito, groq */
    private static String scelto(String fornitore) {
        String nome = Testo.ripulito(fornitore).toLowerCase(Locale.ROOT);
        if (nome.isEmpty()) return GROQ;
        return nome;
    }

    /** L'indirizzo vero del fornitore scelto (con un nome sbagliato non si chiama nessuno) */
    private static String indirizzo(String fornitore) {
        if (scelto(fornitore).equals(ANTHROPIC)) return ANTHROPIC_URL;
        return GROQ_URL;
    }

    public boolean isConfigurato() {
        return !fornitore.isEmpty() && apiKey != null && !apiKey.isBlank();
    }

    private boolean conAnthropic() {
        return fornitore.equals(ANTHROPIC);
    }

    public JsonNode chat(CoachChatRequestDTO req) {
        if (!isConfigurato()) {
            log.warn("Richiesta al Coach AI ma il server non è configurato ({}): risposta 503", causaSpento);
            throw new UpstreamException(HttpStatus.SERVICE_UNAVAILABLE, "Coach AI non configurato sul server (" + causaSpento + ")");
        }
        valida(req);
        // La regola del contesto si aggiunge dopo valida(): i limiti si misurano sui messaggi del client, così una richiesta
        // al limite dei 100.000 caratteri non diventa 400 per colpa della regola (valida() garantisce che sia un elenco)
        ArrayNode messaggi = AmbitoCoach.conRegola((ArrayNode) req.messages());
        JsonNode strumenti = null;
        if (req.tools() != null && !req.tools().isEmpty()) strumenti = req.tools();
        ObjectNode body;
        if (conAnthropic()) body = CoachAnthropic.richiesta(mapper, model, MAX_TOKENS, messaggi, strumenti);
        else body = corpoGroq(messaggi, strumenti);
        String risposta;
        try {
            risposta = http.post()
                    .uri(url)
                    .headers(this::intestazioni)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // Stato e corpo del fornitore restano nei log del server; al client va un messaggio nostro. Il corpo può riportare
            // parti della richiesta dell'utente (e di solito sta su più righe): passa da perLog, così resta su una riga sola.
            // Gli stessi stati per i due fornitori (529, Anthropic sovraccarico, è un 502 come gli altri)
            int stato = e.getStatusCode().value();
            log.warn("{} ha risposto {}: {}", nome, stato, perLog(tronca(e.getResponseBodyAsString())));
            if (stato == 429) throw new UpstreamException(HttpStatus.TOO_MANY_REQUESTS, "Limite richieste Coach AI raggiunto, riprova tra poco");
            if (stato == 400 || stato == 413 || stato == 422) throw new BadRequestException("Richiesta al Coach AI non valida o troppo lunga");
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, "Il servizio AI non è disponibile in questo momento");
        } catch (RestClientException e) {
            // Rete, timeout o risposta interrotta a metà: la causa resta nell'eccezione, che va nel log con il suo stack
            log.warn("{} non raggiungibile o risposta interrotta", nome, e);
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, "Servizio AI non raggiungibile");
        }
        JsonNode letta = leggiRisposta(risposta);
        if (conAnthropic()) {
            // La forma OpenAI che legge il frontend; il filtro del contesto lavora su questa, come con Groq
            letta = CoachAnthropic.risposta(mapper, letta);
            if (letta == null) throw nonValida("non ha l'elenco content", risposta);
        }
        JsonNode filtrata = AmbitoCoach.filtra(letta);
        // Solo il fatto, per contare i rifiuti: mai la domanda né la risposta
        if (filtrata != letta) log.info("Coach: risposta fuori contesto");
        return filtrata;
    }

    /** Il corpo per Groq: i messaggi del client con la regola e gli strumenti così come sono, nel formato OpenAI */
    private ObjectNode corpoGroq(ArrayNode messaggi, JsonNode strumenti) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        // gpt-oss ragiona prima di rispondere e i token di reasoning contano nel budget:
        // effort basso + budget più ampio evitano risposte troncate (content vuoto)
        body.put("max_tokens", MAX_TOKENS);
        // Solo se configurato: un modello che non accetta il campo risponderebbe 400 a ogni richiesta (groq.reasoning-effort vuoto)
        if (!reasoningEffort.isBlank()) body.put("reasoning_effort", reasoningEffort);
        body.set("messages", messaggi);
        if (strumenti != null) {
            body.set("tools", strumenti);
            body.put("tool_choice", "auto");
        }
        return body;
    }

    /** La chiave nell'intestazione del fornitore: Bearer per Groq, x-api-key (con la versione dell'API) per Anthropic */
    private void intestazioni(HttpHeaders intestazioni) {
        if (conAnthropic()) {
            intestazioni.set("x-api-key", apiKey);
            intestazioni.set("anthropic-version", CoachAnthropic.VERSIONE_API);
            return;
        }
        intestazioni.setBearerAuth(apiKey);
    }

    /** La risposta del fornitore è un oggetto JSON: altro (pagina HTML, corpo vuoto o a metà) è un guasto a monte, con il corpo nei log */
    private JsonNode leggiRisposta(String corpo) {
        try {
            JsonNode json = mapper.readTree(corpo);
            if (json.isObject()) return json;
        } catch (JacksonException | IllegalArgumentException _) {
            // Non è JSON (o il corpo manca: readTree(null) lancia IllegalArgumentException): stessa risposta di un JSON non oggetto
        }
        throw nonValida("non è un oggetto JSON", corpo);
    }

    private UpstreamException nonValida(String motivo, String corpo) {
        log.warn("Risposta di {} non valida, {}: {}", nome, motivo, perLog(tronca(corpo)));
        return new UpstreamException(HttpStatus.BAD_GATEWAY, "Il servizio AI ha dato una risposta non valida");
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
            // Il system dev'essere testo: la regola del contesto (AmbitoCoach) va prima e dopo il suo testo, nello stesso messaggio.
            // Con il formato a parti finirebbe in un system a parte e l'ultima parola l'avrebbe il client
            if (SYSTEM.equals(m.path("role").asString()) && !m.path("content").isString()) {
                throw new BadRequestException("Il messaggio system deve avere un content di testo");
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
}
