package com.hoop3x3.backend.security;

import ch.qos.logback.classic.Level;
import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.OrologioDiProva;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.TroppeRichiesteException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.hoop3x3.backend.UtenteDiProva.conId;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il filtro da solo, senza Spring: richieste finte, orologio finto che si sposta a comando, e un resolver finto che
 * ricorda gli errori che il filtro gli affida. Con i limiti abbassati: 3 accessi al minuto, e per
 * il Coach 2 al minuto e 4 al giorno. La stessa regola dentro la catena vera, con i valori di produzione, è in
 * LimiteRichiesteWebTest.
 */
class LimiteRichiesteFilterTest {

    private static final String LOGIN = "/api/auth/login";
    private static final String REGISTRAZIONE = "/api/auth/register";
    private static final String RINNOVO = "/api/auth/refresh";
    private static final String COACH = "/api/coach/chat";
    private static final String INDIRIZZO = "203.0.113.9";

    private final OrologioDiProva orologio = new OrologioDiProva();
    private final List<Exception> errori = new ArrayList<>();
    private final HandlerExceptionResolver resolver = (richiesta, risposta, gestore, errore) -> {
        errori.add(errore);
        return new ModelAndView();
    };
    private final LimiteRichiesteFilter filtro = new LimiteRichiesteFilter(new LimiteRichiesteProperties(3, 2, 4), orologio, resolver);
    private int arrivateAValle;
    private final FilterChain catena = (richiesta, risposta) -> arrivateAValle++;

    private LogCatturato log;

    @BeforeEach
    void catturaIlLog() {
        log = new LogCatturato(LimiteRichiesteFilter.class);
    }

    @AfterEach
    void rilasciaIlLogEIlContestoDiSicurezza() {
        log.close();
        SecurityContextHolder.clearContext(); // l'utente dei test del Coach non resta ai test successivi
    }

    /* ── Aiuti ── */

    /** Una richiesta: true se il filtro l'ha lasciata passare a valle, false se l'ha respinta */
    private boolean passa(String metodo, String percorso, String indirizzo) throws Exception {
        MockHttpServletRequest richiesta = new MockHttpServletRequest(metodo, percorso);
        richiesta.setRemoteAddr(indirizzo);
        int prima = arrivateAValle;
        filtro.doFilter(richiesta, new MockHttpServletResponse(), catena);
        return arrivateAValle > prima;
    }

    private boolean postPassa(String percorso, String indirizzo) throws Exception {
        return passa("POST", percorso, indirizzo);
    }

    /** `quante` POST di seguito dallo stesso indirizzo: tutte devono passare */
    private void postPassano(String percorso, String indirizzo, int quante) throws Exception {
        for (int i = 1; i <= quante; i++) {
            assertThat(postPassa(percorso, indirizzo)).as("richiesta %s a %s", i, percorso).isTrue();
        }
    }

    /** L'errore che il filtro ha affidato al resolver con l'ultima richiesta respinta */
    private TroppeRichiesteException ultimoRifiuto() {
        assertThat(errori).isNotEmpty();
        return (TroppeRichiesteException) errori.getLast();
    }

    /** Da qui in poi la richiesta è di questo utente, come dopo il JwtFilter */
    private static void accediComeUtente(Utente utente) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(utente, null, utente.getAuthorities()));
    }

    /* ── Login, registrazione e rinnovo: per indirizzo, ognuno con il suo contatore ── */

    @ParameterizedTest
    @ValueSource(strings = {LOGIN, REGISTRAZIONE, RINNOVO})
    void laQuartaRichiestaDelMinutoDelloStessoIndirizzo_nonProseguePerLaStradaDegliErrori(String percorso) throws Exception {
        postPassano(percorso, INDIRIZZO, 3);

        assertThat(postPassa(percorso, INDIRIZZO)).isFalse();

        assertThat(arrivateAValle).as("la respinta non arriva a valle: niente BCrypt, niente servizio").isEqualTo(3);
        assertThat(ultimoRifiuto().getSecondiAttesa()).isEqualTo(40);
    }

    @Test
    void passatoIlMinuto_ilLoginTornaConsentito() throws Exception {
        postPassano(LOGIN, INDIRIZZO, 3);
        assertThat(postPassa(LOGIN, INDIRIZZO)).isFalse();

        orologio.avanza(Duration.ofSeconds(40)); // le 10:01:00

        postPassano(LOGIN, INDIRIZZO, 3);
        assertThat(postPassa(LOGIN, INDIRIZZO)).isFalse();
    }

    @Test
    void indirizziDiversi_hannoContatoriSeparati() throws Exception {
        postPassano(LOGIN, "203.0.113.1", 3);
        assertThat(postPassa(LOGIN, "203.0.113.1")).isFalse();

        postPassano(LOGIN, "203.0.113.2", 3);
    }

    // Chi sbaglia la password dieci volte non deve restare senza poter rinnovare la sessione o registrarsi dallo stesso
    // indirizzo: ogni endpoint conta per sé
    @Test
    void accessoRegistrazioneERinnovo_hannoContatoriSeparati() throws Exception {
        postPassano(LOGIN, INDIRIZZO, 3);
        assertThat(postPassa(LOGIN, INDIRIZZO)).isFalse();

        postPassano(REGISTRAZIONE, INDIRIZZO, 3);
        postPassano(RINNOVO, INDIRIZZO, 3);
    }

    // Si contano solo le POST agli endpoint limitati: la GET dello stesso percorso, il logout, le altre API (il controllo di
    // salute di Render compreso, che arriva sempre dallo stesso indirizzo) e un percorso che somiglia soltanto a
    // «/api/auth/login» passano sempre
    @Test
    void ilRestoNonSiContaMai() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(passa("GET", "/actuator/health", INDIRIZZO)).isTrue();
            assertThat(passa("GET", LOGIN, INDIRIZZO)).isTrue();
            assertThat(postPassa("/api/auth/logout", INDIRIZZO)).isTrue();
            assertThat(postPassa("/api/leghe", INDIRIZZO)).isTrue();
            assertThat(postPassa("/api/auth/loginx", INDIRIZZO)).isTrue();
        }
        assertThat(errori).isEmpty();
    }

    // Il percorso lo legge Spring MVC dopo averlo decodificato e senza i parametri (;x=1): se il filtro confrontasse il
    // testo grezzo, «/api/auth/%6Cogin» arriverebbe al login senza essere contato e il limite si aggirerebbe con una lettera
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/%6Cogin", "/api/auth/log%69n", "/api/auth/login;x=1", "/api/auth/login;"})
    void unPercorsoCheSpringTrattaComeIlLogin_contaComeIlLogin(String variante) throws Exception {
        postPassano(variante, INDIRIZZO, 3);

        assertThat(postPassa(LOGIN, INDIRIZZO)).as("le tre richieste della variante sono contate come login").isFalse();
    }

    /* ── Coach AI: per utente, dopo l'autenticazione ── */

    @Test
    void laTerzaRichiestaDelCoachNelMinuto_nonProseguePerLaStradaDegliErrori() throws Exception {
        accediComeUtente(conId("mario@x.it"));
        postPassano(COACH, INDIRIZZO, 2);

        assertThat(postPassa(COACH, INDIRIZZO)).isFalse();

        assertThat(arrivateAValle).isEqualTo(2);
        assertThat(ultimoRifiuto().getSecondiAttesa()).isEqualTo(40);
        assertThat(ultimoRifiuto().getMessage()).isEqualTo("Troppe richieste al Coach AI: riprova tra 40 secondi");
    }

    // Il limite è dell'utente e non dell'indirizzo: due utenti dietro lo stesso indirizzo (una scuola, una palestra)
    // non si tolgono le richieste a vicenda, e lo stesso utente da due indirizzi ha un contatore solo
    @Test
    void utentiDiversi_hannoQuoteSeparateEDaDueIndirizziLUtenteEUno() throws Exception {
        Utente mario = conId("mario@x.it");
        Utente luca = conId("luca@x.it");

        accediComeUtente(mario);
        assertThat(postPassa(COACH, "203.0.113.1")).isTrue();
        assertThat(postPassa(COACH, "203.0.113.2")).isTrue();
        assertThat(postPassa(COACH, "203.0.113.3")).as("terza richiesta di mario, da un altro indirizzo").isFalse();

        accediComeUtente(luca);
        postPassano(COACH, "203.0.113.1", 2);
    }

    // Senza utente (nessun token) il filtro non conta niente: risponde l'autorizzazione con il 401. Se contasse, chiunque
    // potrebbe fare 429 a un altro utente, e le richieste anonime riempirebbero la mappa
    @Test
    void senzaUtente_ilCoachNonSiConta() throws Exception {
        postPassano(COACH, INDIRIZZO, 10);
        assertThat(errori).isEmpty();
    }

    @Test
    void conUnPrincipalCheNonEUnUtente_ilCoachNonSiConta() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));

        postPassano(COACH, INDIRIZZO, 10);
        assertThat(errori).isEmpty();
    }

    // Solo la chat chiama Groq: lo stato del Coach, che il frontend legge a ogni avvio, non consuma la quota
    @Test
    void soloLaChatDelCoachSiConta() throws Exception {
        accediComeUtente(conId("mario@x.it"));
        for (int i = 0; i < 10; i++) {
            assertThat(passa("GET", "/api/coach/status", INDIRIZZO)).isTrue();
            assertThat(passa("GET", COACH, INDIRIZZO)).isTrue();
        }
    }

    @Test
    void laQuotaGiornalieraDelCoach_finisceAMezzanotteUtc() throws Exception {
        accediComeUtente(conId("mario@x.it"));
        postPassano(COACH, INDIRIZZO, 2); // 10:00:20
        orologio.avanza(Duration.ofMinutes(1));
        postPassano(COACH, INDIRIZZO, 2); // 10:01:20: 4 richieste nel giorno, le sue 4

        orologio.avanza(Duration.ofMinutes(1));
        assertThat(postPassa(COACH, INDIRIZZO)).as("il minuto è nuovo, la quota del giorno no").isFalse();

        TroppeRichiesteException rifiuto = ultimoRifiuto();
        assertThat(rifiuto.getSecondiAttesa()).isEqualTo(13 * 3600 + 57 * 60 + 40); // dalle 10:02:20 a mezzanotte
        assertThat(rifiuto.getMessage()).isEqualTo("Quota giornaliera del Coach AI esaurita: riprova tra 14 ore");

        orologio.avanza(Duration.ofSeconds(rifiuto.getSecondiAttesa())); // mezzanotte UTC
        postPassano(COACH, INDIRIZZO, 2);
    }

    // La quota giornaliera conta le richieste che passano: chi insiste oltre il limite del minuto non la consuma con quelle
    // respinte, altrimenti pochi secondi di insistenza gli costerebbero la giornata
    @Test
    void leRichiesteRespinteDalMinuto_nonConsumanoLaQuotaGiornaliera() throws Exception {
        accediComeUtente(conId("mario@x.it"));
        postPassano(COACH, INDIRIZZO, 2);
        for (int i = 0; i < 20; i++) {
            assertThat(postPassa(COACH, INDIRIZZO)).isFalse();
        }

        orologio.avanza(Duration.ofMinutes(1));
        postPassano(COACH, INDIRIZZO, 2); // 4 passate nel giorno: tutte e due ancora dentro la quota di 4

        orologio.avanza(Duration.ofMinutes(1));
        assertThat(postPassa(COACH, INDIRIZZO)).as("la quota è consumata dalle sole 4 passate").isFalse();
        assertThat(ultimoRifiuto().getMessage()).startsWith("Quota giornaliera del Coach AI esaurita");
    }

    /* ── Il log: una riga per chiave e per finestra ── */

    @Test
    void chiInsiste_lasciaUnaRigaWarnPerFinestraNonUnaPerRichiesta() throws Exception {
        postPassano(LOGIN, INDIRIZZO, 3);
        for (int i = 0; i < 10; i++) {
            assertThat(postPassa(LOGIN, INDIRIZZO)).isFalse();
        }
        assertThat(log.righe()).containsExactly(
                "Limite di richieste superato: Troppi tentativi di accesso (massimo 3 al minuto), indirizzo " + INDIRIZZO);
        assertThat(log.livelli()).containsOnly(Level.WARN);

        // Un altro indirizzo ha la sua riga; e nel minuto dopo chi insiste ancora ne lascia un'altra
        postPassano(LOGIN, "203.0.113.1", 3);
        assertThat(postPassa(LOGIN, "203.0.113.1")).isFalse();
        orologio.avanza(Duration.ofMinutes(1));
        postPassano(LOGIN, INDIRIZZO, 3);
        assertThat(postPassa(LOGIN, INDIRIZZO)).isFalse();

        assertThat(log.righe()).hasSize(3);
    }

    @Test
    void richiestePassate_nonLasciaNessunaRiga() throws Exception {
        postPassano(LOGIN, INDIRIZZO, 3);
        postPassano(REGISTRAZIONE, INDIRIZZO, 3);

        assertThat(log.righe()).isEmpty();
    }

    // L'indirizzo può arrivare da un'intestazione del proxy (server.forward-headers-strategy): con un a capo dentro, una
    // riga di log ne diventerebbe due e la seconda sarebbe inventata da chi manda la richiesta
    @Test
    void indirizzoConACapo_nelLogSiScriveInUnaRigaSola() throws Exception {
        String ostile = "203.0.113.9\r\nERROR riga inventata";
        postPassano(LOGIN, ostile, 3);

        assertThat(postPassa(LOGIN, ostile)).isFalse();

        assertThat(log.righe()).singleElement().satisfies(riga ->
                assertThat(riga).doesNotContain("\r", "\n").endsWith("indirizzo 203.0.113.9\\r\\nERROR riga inventata"));
    }

    @Test
    void ilCoach_nelLogHaLUtenteELaQuotaSuperata() throws Exception {
        Utente mario = conId("mario@x.it");
        accediComeUtente(mario);
        postPassano(COACH, INDIRIZZO, 2);
        assertThat(postPassa(COACH, INDIRIZZO)).isFalse(); // oltre il minuto
        orologio.avanza(Duration.ofMinutes(1));
        postPassano(COACH, INDIRIZZO, 2); // 4 passate nel giorno
        orologio.avanza(Duration.ofMinutes(1));
        assertThat(postPassa(COACH, INDIRIZZO)).isFalse(); // oltre il giorno
        assertThat(postPassa(COACH, INDIRIZZO)).isFalse(); // insiste: nessuna riga in più

        assertThat(log.righe()).containsExactly(
                "Limite di richieste superato: Troppe richieste al Coach AI (massimo 2 al minuto), utente " + mario.getId(),
                "Limite di richieste superato: Quota giornaliera del Coach AI esaurita (massimo 4 al giorno), utente " + mario.getId());
    }
}
