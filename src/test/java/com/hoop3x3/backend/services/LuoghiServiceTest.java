package com.hoop3x3.backend.services;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.dto.LuogoDTO;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.exceptions.UpstreamException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La ricerca di un luogo con Nominatim finto: il risultato e i suoi campi, il non trovato (404), gli errori (502 senza il corpo
 * di Nominatim), la cache anche per i 404, e il limite di una richiesta al secondo verso Nominatim.
 */
class LuoghiServiceTest {

    private static final String UA = "HOOP3X3/1.0 (progetto HOOP 3X3)";
    private static final String FIRENZE = "[{\"lat\":\"43.7710\",\"lon\":\"11.2486\",\"display_name\":\"Firenze, Toscana, Italia\"}]";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private NominatimFinto nominatim;

    @BeforeEach
    void avviaNominatimFinto() throws IOException {
        nominatim = new NominatimFinto();
    }

    @AfterEach
    void fermaNominatimFinto() {
        nominatim.close();
    }

    private LuoghiService servizio() {
        return new LuoghiService(nominatim.url(), UA, TIMEOUT, TIMEOUT, JsonMapper.builder().build());
    }

    /* ── Il risultato ── */

    // Il primo pezzo del nome (prima della virgola), le coordinate come numeri, e la richiesta con i parametri fissi, lo User-Agent
    // dichiarato e Accept: application/json
    @Test
    void trovato_restituisceNomeECoordinateEInviaIParametriFissi() {
        nominatim.risponde(200, "application/json", FIRENZE);

        LuogoDTO luogo = servizio().cerca("firenze");

        assertThat(luogo).isEqualTo(new LuogoDTO("Firenze", 43.771, 11.2486));
        assertThat(nominatim.richieste()).singleElement().satisfies(richiesta -> {
            assertThat(richiesta.userAgent()).isEqualTo(UA);
            assertThat(richiesta.accept()).isEqualTo("application/json");
            assertThat(URLDecoder.decode(richiesta.query(), UTF_8))
                    .contains("format=json", "limit=1", "accept-language=it", "q=firenze");
        });
    }

    // Il testo della ricerca arriva intatto a Nominatim anche con caratteri che in un URL sono speciali
    @Test
    void testoConCaratteriSpeciali_arrivaIntattoANominatim() {
        nominatim.risponde(200, "application/json", "[]");

        assertThatThrownBy(() -> servizio().cerca("a&b=c+d e"))
                .isInstanceOf(NotFoundException.class);

        // Il «+» deve arrivare come «+» (non come spazio) e la «&» come parte del testo
        assertThat(nominatim.richieste()).singleElement().satisfies(richiesta ->
                assertThat(URLDecoder.decode(richiesta.query(), UTF_8)).contains("q=a&b=c+d e"));
    }

    /* ── Il non trovato e gli errori ── */

    // Nominatim risponde un elenco vuoto: 404 «Luogo non trovato»
    @Test
    void nonTrovato_404ConIlMessaggio() {
        nominatim.risponde(200, "application/json", "[]");

        assertThatThrownBy(() -> servizio().cerca("zzzzqq"))
                .isInstanceOfSatisfying(NotFoundException.class, e -> assertThat(e.getMessage()).isEqualTo("Luogo non trovato"));
    }

    // Nominatim risponde con un errore e un corpo: al client va un messaggio generico, il corpo non esce, e nel log resta solo lo stato
    @Test
    void erroreDiNominatim_502SenzaIlCorpoNelMessaggioNeiLog() {
        nominatim.risponde(503, "text/html", "<html>Servizio non disponibile: troppe richieste dall'IP</html>");

        try (LogCatturato log = new LogCatturato(LuoghiService.class)) {
            assertThatThrownBy(() -> servizio().cerca("firenze"))
                    .isInstanceOfSatisfying(UpstreamException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(e.getMessage()).doesNotContain("troppe richieste");
                    });
            assertThat(log.righe()).isNotEmpty().allSatisfy(riga -> assertThat(riga).doesNotContain("troppe richieste"));
        }
    }

    // Una risposta che non è un elenco (un oggetto, un testo) è un guasto di Nominatim: 502
    @Test
    void rispostaCheNonEUnElenco_502() {
        nominatim.risponde(200, "application/json", "{\"errore\":\"boh\"}");

        assertThatThrownBy(() -> servizio().cerca("firenze"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // Nominatim non raggiungibile (il finto è fermo): 502 generico
    @Test
    void reteNonRaggiungibile_502() {
        String url = nominatim.url();
        nominatim.close();
        LuoghiService fermo = new LuoghiService(url, UA, TIMEOUT, TIMEOUT, JsonMapper.builder().build());

        assertThatThrownBy(() -> fermo.cerca("firenze"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    /* ── La cache e il limite ── */

    // Lo stesso luogo, scritto con maiuscole, spazi doppi o ai bordi, non chiama Nominatim due volte
    @Test
    void cache_laChiaveNormalizzataEvitaLaSecondaChiamata() {
        nominatim.risponde(200, "application/json", FIRENZE);
        LuoghiService servizio = servizio();

        servizio.cerca("Firenze");
        servizio.cerca("  firenze   ");

        assertThat(nominatim.richieste()).hasSize(1);
    }

    // Anche il non trovato va in cache: la seconda ricerca non chiama Nominatim
    @Test
    void cache_ancheIlNonTrovatoVaInCache() {
        nominatim.risponde(200, "application/json", "[]");
        LuoghiService servizio = servizio();

        assertThatThrownBy(() -> servizio.cerca("zzzzqq")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> servizio.cerca("zzzzqq")).isInstanceOf(NotFoundException.class);

        assertThat(nominatim.richieste()).hasSize(1);
    }

    // Due ricerche diverse una dopo l'altra: la seconda parte almeno un secondo dopo la prima (politica d'uso di Nominatim)
    @Test
    void limite_unaRichiestaAlSecondoVersoNominatim() {
        nominatim.risponde(200, "application/json", "[]");
        LuoghiService servizio = servizio();

        assertThatThrownBy(() -> servizio.cerca("firenze")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> servizio.cerca("roma")).isInstanceOf(NotFoundException.class);

        assertThat(nominatim.richieste()).hasSize(2);
        long distanza = nominatim.richieste().get(1).istante() - nominatim.richieste().get(0).istante();
        assertThat(distanza).as("attesa tra le due richieste").isGreaterThanOrEqualTo(Duration.ofMillis(950).toNanos());
    }
}
