package com.hoop3x3.backend.services;

import com.hoop3x3.backend.exceptions.UpstreamException;
import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;

import static com.hoop3x3.backend.services.LettoreFoglio.FORNITORE_NON_DISPONIBILE;
import static com.hoop3x3.backend.services.LettoreFoglio.FORNITORE_NON_RAGGIUNGIBILE;
import static com.hoop3x3.backend.services.LettoreFoglio.LIMITE_FORNITORE;
import static com.hoop3x3.backend.services.LettoreFoglio.RISPOSTA_NON_VALIDA;
import static com.hoop3x3.backend.services.LogSupport.tronca;

/**
 * La chiamata HTTP di un lettore dei fogli, uguale per Groq e Anthropic: timeout, errori del fornitore tradotti nei messaggi
 * del contratto, e nei log i corpi troncati, su una riga e senza l'immagine. Le righe di log vanno nel logger del lettore.
 */
final class ChiamataFornitore {

    private static final String BASE64 = "base64,";

    private final String fornitore;
    private final Logger log;
    private final ObjectMapper mapper;
    private final String url;
    private final RestClient http;

    ChiamataFornitore(String fornitore, Logger log, ObjectMapper mapper, String url, Duration timeoutConnessione,
                      Duration timeoutRisposta) {
        this.fornitore = fornitore;
        this.log = log;
        this.mapper = mapper;
        this.url = url;
        // Come per il Coach: senza timeout un modello lento terrebbe occupato un thread del server a tempo indeterminato
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeoutConnessione).build());
        factory.setReadTimeout(timeoutRisposta);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** POST del corpo con le intestazioni del fornitore (la chiave): la risposta, che dev'essere un oggetto JSON */
    JsonNode invia(Consumer<HttpHeaders> intestazioni, ObjectNode corpo) {
        String risposta;
        try {
            risposta = http.post()
                    .uri(url)
                    .headers(intestazioni)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corpo.toString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // Anche un 400 (schema rifiutato, modello dismesso) è un guasto della configurazione del server, non della foto:
            // al client un 502 con un messaggio nostro, a chi gestisce il server lo stato e il corpo nei log
            int stato = e.getStatusCode().value();
            log.warn("{} ha risposto {} alla lettura di un foglio: {}", fornitore, stato,
                    corpoPerLog(e.getResponseBodyAsString()));
            if (stato == 429) throw new UpstreamException(HttpStatus.TOO_MANY_REQUESTS, LIMITE_FORNITORE);
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, FORNITORE_NON_DISPONIBILE);
        } catch (RestClientException e) {
            // Rete, timeout o risposta a metà: la causa resta nel log con il suo stack (l'eccezione non porta il corpo inviato)
            log.warn("{} non raggiungibile o risposta interrotta durante la lettura di un foglio", fornitore, e);
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, FORNITORE_NON_RAGGIUNGIBILE);
        }
        JsonNode json = json(risposta);
        if (json == null || !json.isObject()) throw nonValida(risposta);
        return json;
    }

    /** La bozza scritta dal modello come testo JSON (il content di Groq) */
    JsonNode bozza(String testo) {
        JsonNode letta = json(testo);
        if (!SchemaFoglio.haLaForma(letta)) throw nonValida(testo);
        return letta;
    }

    /** La bozza scritta dal modello come oggetto JSON (l'input dello strumento di Anthropic) */
    JsonNode bozza(JsonNode letta) {
        if (!SchemaFoglio.haLaForma(letta)) throw nonValida(String.valueOf(letta));
        return letta;
    }

    /** 502 «risposta non valida», con ciò che è arrivato nei log */
    UpstreamException nonValida(String arrivato) {
        log.warn("Risposta di {} non valida per la lettura di un foglio: {}", fornitore, corpoPerLog(arrivato));
        return new UpstreamException(HttpStatus.BAD_GATEWAY, RISPOSTA_NON_VALIDA);
    }

    /** Il testo come JSON, o null se non lo è (anche un corpo assente) */
    private JsonNode json(String testo) {
        if (testo == null) return null;
        try {
            return mapper.readTree(testo);
        } catch (JacksonException _) {
            return null;
        }
    }

    /**
     * Un corpo del fornitore pronto per i log: al massimo 500 caratteri e su una riga. Il fornitore non rimanda l'immagine, ma
     * se un errore la riportasse tutto ciò che segue «base64,» resterebbe fuori
     */
    private static String corpoPerLog(String corpo) {
        String testo = corpo;
        if (testo != null && testo.contains(BASE64)) testo = testo.substring(0, testo.indexOf(BASE64) + BASE64.length()) + "…";
        return LogSupport.perLog(tronca(testo));
    }
}
