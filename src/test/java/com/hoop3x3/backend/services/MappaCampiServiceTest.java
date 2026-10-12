package com.hoop3x3.backend.services;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.exceptions.UpstreamException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URLDecoder;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Il servizio della mappa con Google finto: la chiave che manca, l'immagine e i suoi parametri, gli errori di Google (che non
 * devono portare la chiave né nel messaggio né nei log), la cache e il suo limite. Nessuna chiamata esce dal computer.
 */
class MappaCampiServiceTest {

    private static final String CHIAVE = "CHIAVE-FINTA-123";
    /** Una chiave che non deve mai comparire in un messaggio d'errore o in un log */
    private static final String CHIAVE_SEGRETA = "CHIAVE-SEGRETA-999";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private GoogleFinto google;

    @BeforeEach
    void avviaGoogleFinto() throws IOException {
        google = new GoogleFinto();
    }

    @AfterEach
    void fermaGoogleFinto() {
        google.close();
    }

    private MappaCampiService servizio(String chiave) {
        return new MappaCampiService(chiave, google.url(), TIMEOUT, TIMEOUT);
    }

    /* ── La chiave ── */

    // Senza chiave la mappa non si può fare: 503 con un messaggio in italiano, e nessuna richiesta parte verso Google
    @Test
    void chiaveAssente_503SenzaChiamareGoogle() {
        assertThatThrownBy(() -> servizio("").immagine(41.9028, 12.4964, 12))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).isEqualTo(MappaCampiService.MESSAGGIO_CHIAVE_ASSENTE);
                });
        assertThat(google.richieste()).isEmpty();
    }

    /* ── L'immagine ── */

    // Google riceve il centro arrotondato a 5 decimali, lo zoom, la dimensione fissa 640x640 a scala 2 e la chiave; l'immagine
    // torna così com'è
    @Test
    void rispostaDiGoogle_restituisceLImmagineEInviaIParametriFissi() {
        byte[] immagine = servizio(CHIAVE).immagine(41.9028037, 12.4964, 12);

        assertThat(immagine).isEqualTo(GoogleFinto.PNG);
        // La query arriva con la virgola codificata (%2C): Google la legge uguale, quindi la si decodifica per il confronto
        assertThat(google.richieste()).singleElement().satisfies(richiesta -> {
            assertThat(URLDecoder.decode(richiesta.query(), UTF_8)).contains("center=41.90280,12.49640", "zoom=12",
                    "size=640x640", "scale=2", "key=" + CHIAVE);
        });
    }

    // Una risposta che non è un'immagine (una pagina d'errore con 200, un corpo vuoto) è un guasto di Google: 502
    @Test
    void rispostaSenzaImmagine_502() {
        google.risponde(200, "text/html", "<html>errore</html>".getBytes(UTF_8));

        assertThatThrownBy(() -> servizio(CHIAVE).immagine(41.9028, 12.4964, 12))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    /* ── Gli errori: la chiave non esce ── */

    // Google risponde 403 e il suo corpo cita la chiave: al client va un messaggio nostro, la chiave non c'è né nel messaggio né
    // nelle righe di log; nel log resta solo lo stato
    @Test
    void erroreDiGoogle_502_eLaChiaveNonEscePeNelMessaggioNeNeiLog() {
        google.risponde(403, "text/plain", ("La chiave " + CHIAVE_SEGRETA + " non è valida").getBytes(UTF_8));

        try (LogCatturato log = new LogCatturato(MappaCampiService.class)) {
            assertThatThrownBy(() -> servizio(CHIAVE_SEGRETA).immagine(41.9028, 12.4964, 12))
                    .isInstanceOfSatisfying(UpstreamException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(e.getMessage()).doesNotContain(CHIAVE_SEGRETA);
                    });
            assertThat(log.righe()).isNotEmpty().allSatisfy(riga -> assertThat(riga).doesNotContain(CHIAVE_SEGRETA));
        }
    }

    // La rete non arriva a Google (il server finto è fermo): l'eccezione di rete porta l'URL con la chiave nel messaggio, e la
    // chiave non deve uscire né dal servizio né dai log
    @Test
    void reteNonRaggiungibile_502SenzaLaChiaveNelLog() {
        String url = google.url();
        google.close();
        MappaCampiService fermo = new MappaCampiService(CHIAVE_SEGRETA, url, TIMEOUT, TIMEOUT);

        try (LogCatturato log = new LogCatturato(MappaCampiService.class)) {
            assertThatThrownBy(() -> fermo.immagine(41.9028, 12.4964, 12))
                    .isInstanceOfSatisfying(UpstreamException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(e.getMessage()).doesNotContain(CHIAVE_SEGRETA);
                    });
            assertThat(log.righe()).isNotEmpty().allSatisfy(riga -> assertThat(riga).doesNotContain(CHIAVE_SEGRETA));
        }
    }

    /* ── La cache ── */

    // La stessa posizione (anche con la differenza oltre il quinto decimale) e lo stesso zoom non chiamano Google due volte
    @Test
    void cache_laSecondaRichiestaUgualeNonChiamaGoogle() {
        MappaCampiService servizio = servizio(CHIAVE);

        servizio.immagine(41.902800, 12.496400, 12);
        servizio.immagine(41.9028004, 12.4964004, 12);

        assertThat(google.richieste()).hasSize(1);
    }

    // Un altro zoom è un'altra immagine
    @Test
    void cache_unAltroZoomChiamaGoogleDiNuovo() {
        MappaCampiService servizio = servizio(CHIAVE);

        servizio.immagine(41.9028, 12.4964, 12);
        servizio.immagine(41.9028, 12.4964, 13);

        assertThat(google.richieste()).hasSize(2);
    }

    // La cache tiene al massimo 200 voci: con 201 posizioni diverse la prima esce, e la si chiede di nuovo; l'ultima è ancora lì
    @Test
    void cache_limitataA200Voci_laPiuVecchiaEsce() {
        MappaCampiService servizio = servizio(CHIAVE);
        for (int i = 0; i <= 200; i++) {
            servizio.immagine(40 + i * 0.001, 12.5, 12);
        }
        assertThat(google.richieste()).hasSize(201);

        servizio.immagine(40, 12.5, 12); // la prima: uscita dalla cache
        assertThat(google.richieste()).hasSize(202);

        servizio.immagine(40 + 200 * 0.001, 12.5, 12); // l'ultima: ancora in cache
        assertThat(google.richieste()).hasSize(202);
    }
}
