package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.LuogoDTO;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.exceptions.UpstreamException;
import lombok.extern.slf4j.Slf4j;
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

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * La ricerca di un luogo (la città dove spostare la mappa) con Nominatim, il geocoding di OpenStreetMap, chiamato dal server:
 * il browser non parla con Nominatim, e così la politica d'uso è rispettata da un punto solo. Nominatim chiede al massimo una
 * richiesta al secondo: le chiamate si fanno una alla volta, a distanza di un secondo, in tutto il server (non per utente). Il
 * risultato, anche il non trovato, va in una cache di 500 voci, così la stessa ricerca non torna a Nominatim.
 */
@Slf4j
@Service
@EnableConfigurationProperties(NominatimProperties.class)
public class LuoghiService {

    /** L'indirizzo della ricerca di Nominatim: il client non lo sceglie */
    static final String URL_NOMINATIM = "https://nominatim.openstreetmap.org/search";
    /** Quanti luoghi tenere in cache, anche i non trovati: oltre, esce il meno usato */
    static final int MASSIMO_LUOGHI_CACHE = 500;
    /** La politica d'uso di Nominatim: una richiesta al secondo dal server, in tutto */
    static final long INTERVALLO_NANOS = Duration.ofSeconds(1).toNanos();
    static final String MESSAGGIO_NON_TROVATO = "Luogo non trovato";
    private static final String MESSAGGIO_ERRORE = "La ricerca del luogo non è disponibile in questo momento, riprova tra poco";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final String url;
    private final String userAgent;
    private final RestClient http;
    private final ObjectMapper mapper;
    private final Map<String, Optional<LuogoDTO>> cache = new CacheLimitata();
    /** Il turno delle chiamate a Nominatim: una alla volta, e l'istante dell'ultima (nanoTime), entrambi sotto questo lock */
    private final Object turno = new Object();
    private long ultimaChiamata;

    @org.springframework.beans.factory.annotation.Autowired
    public LuoghiService(NominatimProperties proprieta, ObjectMapper mapper) {
        this(URL_NOMINATIM, proprieta.userAgent(), TIMEOUT, TIMEOUT, mapper);
    }

    /** Per i test: Nominatim finto su un indirizzo locale e timeout brevi */
    LuoghiService(String url, String userAgent, Duration timeoutConnessione, Duration timeoutRisposta, ObjectMapper mapper) {
        this.url = url;
        this.userAgent = userAgent;
        this.mapper = mapper;
        // La prima chiamata non aspetta: l'istante di partenza è un intervallo fa
        this.ultimaChiamata = System.nanoTime() - INTERVALLO_NANOS;
        // Senza timeout una risposta lenta di Nominatim terrebbe bloccato il turno di tutte le ricerche
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeoutConnessione).build());
        factory.setReadTimeout(timeoutRisposta);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Il luogo cercato. Lancia NotFoundException se Nominatim non lo conosce, UpstreamException se Nominatim non risponde */
    public LuogoDTO cerca(String q) {
        String chiave = chiaveCache(q);
        Optional<LuogoDTO> trovato = leggiCache(chiave);
        if (trovato == null) {
            synchronized (turno) {
                // Doppio controllo: una ricerca uguale che era in fila prima di noi ha già riempito la cache
                trovato = leggiCache(chiave);
                if (trovato == null) {
                    attendiIlTurno();
                    trovato = chiamaNominatim(q);
                    scriviCache(chiave, trovato);
                }
            }
        }
        return trovato.orElseThrow(() -> new NotFoundException(MESSAGGIO_NON_TROVATO));
    }

    /** Il testo come chiave della cache: senza spazi ai bordi, con gli spazi doppi ridotti a uno, in minuscolo */
    private static String chiaveCache(String q) {
        return q.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Il risultato in cache, Optional.empty() se il luogo non c'è, null se non è in cache */
    private Optional<LuogoDTO> leggiCache(String chiave) {
        synchronized (cache) {
            return cache.get(chiave);
        }
    }

    private void scriviCache(String chiave, Optional<LuogoDTO> risultato) {
        synchronized (cache) {
            cache.put(chiave, risultato);
        }
    }

    /** Aspetta, se serve, che sia passato un secondo dall'ultima chiamata. Chiamato con il lock del turno */
    private void attendiIlTurno() {
        long attesa = ultimaChiamata + INTERVALLO_NANOS - System.nanoTime();
        if (attesa > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(attesa);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException(HttpStatus.BAD_GATEWAY, MESSAGGIO_ERRORE);
            }
        }
        ultimaChiamata = System.nanoTime();
    }

    private Optional<LuogoDTO> chiamaNominatim(String q) {
        // Il testo si codifica qui: URL e query non lo fanno bene per il «+» (lo lascerebbero com'è e Nominatim lo leggerebbe come
        // spazio), e per la «&» e l'«=» dentro il testo
        URI uri = URI.create(url + "?format=json&limit=1&accept-language=it&q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8));
        String corpo;
        try {
            corpo = http.get()
                    .uri(uri)
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // Il corpo di Nominatim non esce né nel messaggio né nei log: solo lo stato
            log.warn("Nominatim ha risposto {}", e.getStatusCode().value());
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, MESSAGGIO_ERRORE);
        } catch (RestClientException e) {
            // L'eccezione di rete porta nel messaggio l'URL con la ricerca: nel log va solo il tipo
            log.warn("Nominatim non raggiungibile ({})", e.getClass().getSimpleName());
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, MESSAGGIO_ERRORE);
        }
        return leggi(corpo, q);
    }

    /** L'elenco di Nominatim: vuoto se il luogo non c'è, altrimenti il primo risultato con il nome e il punto */
    private Optional<LuogoDTO> leggi(String corpo, String q) {
        JsonNode radice;
        try {
            radice = mapper.readTree(corpo);
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Nominatim ha risposto qualcosa che non è JSON");
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, MESSAGGIO_ERRORE);
        }
        if (radice == null || !radice.isArray()) {
            log.warn("Nominatim ha risposto un JSON che non è un elenco");
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, MESSAGGIO_ERRORE);
        }
        if (radice.isEmpty()) return Optional.empty();
        JsonNode primo = radice.get(0);
        try {
            double lat = Double.parseDouble(primo.path("lat").asString(""));
            double lng = Double.parseDouble(primo.path("lon").asString(""));
            // Il nome completo è «Firenze, Toscana, Italia»: per il titolo basta il primo pezzo, altrimenti la ricerca stessa
            String nomeCompleto = primo.path("display_name").asString("");
            String nome = nomeCompleto.split(",")[0].trim();
            if (nome.isEmpty()) nome = q.trim();
            return Optional.of(new LuogoDTO(nome, lat, lng));
        } catch (NumberFormatException e) {
            log.warn("Nominatim ha risposto un luogo senza coordinate valide");
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, MESSAGGIO_ERRORE);
        }
    }

    /** La cache: al massimo MASSIMO_LUOGHI_CACHE voci, e a ogni lettura la voce usata va in fondo (esce la meno usata) */
    private static final class CacheLimitata extends LinkedHashMap<String, Optional<LuogoDTO>> {
        CacheLimitata() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<LuogoDTO>> voceVecchia) {
            return size() > MASSIMO_LUOGHI_CACHE;
        }
    }
}
