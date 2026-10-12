package com.hoop3x3.backend.services;

import com.hoop3x3.backend.exceptions.UpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * La mappa di un punto per i campetti: un'immagine di Google Static Maps chiesta dal server, così la chiave resta qui e non
 * nel sito. Le immagini si tengono in una cache piccola (le posizioni si ripetono: la stessa zona si apre più volte) e la
 * chiave non esce mai: non nelle risposte d'errore, non nei log, non nell'URL di nessuna risposta.
 */
@Slf4j
@Service
@EnableConfigurationProperties(MappeProperties.class)
public class MappaCampiService {

    /** L'indirizzo dell'API Maps Static: il client non lo sceglie */
    static final String URL_GOOGLE = "https://maps.googleapis.com/maps/api/staticmap";
    /** Il messaggio del 503 quando la chiave manca: lo legge anche AccessoEndpointIT */
    public static final String MESSAGGIO_CHIAVE_ASSENTE = "La mappa non è disponibile: sul server manca la chiave MAPS_API_KEY";
    private static final String MESSAGGIO_ERRORE_GOOGLE = "La mappa non è disponibile in questo momento, riprova tra poco";
    /** Quante immagini tenere in memoria: oltre, esce la più vecchia */
    static final int MASSIMO_IMMAGINI = 200;
    /** Le coordinate si arrotondano a 5 decimali (circa un metro): la cache e la richiesta a Google usano lo stesso punto */
    private static final double SCALA_COORDINATE = 1e5;
    private static final Duration TIMEOUT_CONNESSIONE = Duration.ofSeconds(5);
    private static final Duration TIMEOUT_RISPOSTA = Duration.ofSeconds(10);

    private final String chiave;
    private final String url;
    private final RestClient http;
    private final Map<String, byte[]> cache = new CacheLimitata();

    @org.springframework.beans.factory.annotation.Autowired
    public MappaCampiService(MappeProperties proprieta) {
        this(proprieta.staticApiKey(), URL_GOOGLE, TIMEOUT_CONNESSIONE, TIMEOUT_RISPOSTA);
    }

    /** Per i test: Google finto su un indirizzo locale e timeout brevi */
    MappaCampiService(String chiave, String url, Duration timeoutConnessione, Duration timeoutRisposta) {
        this.chiave = chiave == null ? "" : chiave;
        this.url = url;
        // Senza timeout una risposta lenta di Google terrebbe occupato un thread del server a tempo indeterminato
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeoutConnessione).build());
        factory.setReadTimeout(timeoutRisposta);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Il PNG della mappa del punto, a quello zoom. Lancia UpstreamException: 503 senza chiave, 502 se Google non risponde */
    public byte[] immagine(double lat, double lng, int zoom) {
        double latArrotondata = arrotonda(lat);
        double lngArrotondata = arrotonda(lng);
        if (chiave.isBlank()) {
            // Niente richieste a Google senza chiave: il 503 è la risposta, e il messaggio non contiene nulla della chiave
            throw new UpstreamException(HttpStatus.SERVICE_UNAVAILABLE, MESSAGGIO_CHIAVE_ASSENTE);
        }
        String chiaveCache = String.format(Locale.ROOT, "%.5f,%.5f,%d", latArrotondata, lngArrotondata, zoom);
        synchronized (cache) {
            byte[] trovata = cache.get(chiaveCache);
            if (trovata != null) return trovata;
        }
        // Fuori dal lock: una richiesta lenta a Google non blocca le altre immagini della cache
        byte[] png = scarica(latArrotondata, lngArrotondata, zoom);
        synchronized (cache) {
            cache.put(chiaveCache, png);
        }
        return png;
    }

    private byte[] scarica(double lat, double lng, int zoom) {
        String centro = String.format(Locale.ROOT, "%.5f,%.5f", lat, lng);
        try {
            var risposta = http.get()
                    .uri(url + "?center={centro}&zoom={zoom}&size=640x640&scale=2&maptype=roadmap&key={chiave}",
                            centro, zoom, chiave)
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] png = risposta.getBody();
            boolean immagine = risposta.getHeaders().getContentType() != null
                    && risposta.getHeaders().getContentType().getType().equals(MediaType.IMAGE_PNG.getType());
            if (png == null || png.length == 0 || !immagine) {
                log.warn("Mappa: Google ha risposto senza un'immagine PNG");
                throw new UpstreamException(HttpStatus.BAD_GATEWAY, MESSAGGIO_ERRORE_GOOGLE);
            }
            return png;
        } catch (RestClientResponseException e) {
            // Il corpo di Google può citare la chiave e l'URL della richiesta la contiene: nei log va solo lo stato
            log.warn("Mappa: Google ha risposto {}", e.getStatusCode().value());
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, MESSAGGIO_ERRORE_GOOGLE);
        } catch (RestClientException e) {
            // L'eccezione di rete porta nel messaggio l'URL completo, con la chiave: nel log va solo il tipo
            log.warn("Mappa: Google non raggiungibile ({})", e.getClass().getSimpleName());
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, MESSAGGIO_ERRORE_GOOGLE);
        }
    }

    /** Le coordinate arrotondate a 5 decimali: la chiave della cache e la richiesta a Google sono le stesse */
    private static double arrotonda(double valore) {
        return Math.round(valore * SCALA_COORDINATE) / SCALA_COORDINATE;
    }

    /** Una cache che tiene al massimo MASSIMO_IMMAGINI voci, e a ogni lettura porta in fondo quella usata: esce la meno usata */
    private static final class CacheLimitata extends LinkedHashMap<String, byte[]> {
        CacheLimitata() {
            super(16, 0.75f, true); // accessOrder: ogni get sposta la voce in fondo
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> voceVecchia) {
            return size() > MASSIMO_IMMAGINI;
        }
    }
}
