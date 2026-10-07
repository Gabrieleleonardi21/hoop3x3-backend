package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.MondoDiProva;
import com.hoop3x3.backend.MondoDiProva.Mondo;
import com.hoop3x3.backend.TappaDiProva;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JWTtools;
import com.hoop3x3.backend.services.AccessGuard;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.LegaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chi può fare che cosa, endpoint per endpoint, con il database vero e la catena di sicurezza vera (JWT compreso): la tabella
 * delle regole di accesso in un posto solo. Ogni endpoint protetto risponde 401 senza token. Ogni endpoint che agisce su una
 * risorsa risponde 403 a chi non ne è il proprietario (né ADMIN), 404 se la risorsa non esiste, e funziona per il proprietario
 * e per un ADMIN: queste ultime sono la prova che le richieste dei casi di rifiuto sono valide, altrimenti un 403 o un 404
 * potrebbe nascondere un 400. Un rifiuto non scrive niente: il database resta com'era.
 * <p>
 * I casi più fini (messaggi, ordine dei controlli, righe di log) li provano le classi dedicate: ArchivioIT, VersioneTappeIT,
 * LogApplicativiTest. Un endpoint nuovo si aggiunge a una delle due liste qui sotto.
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class AccessoEndpointIT {

    /**
     * Un endpoint con il corpo che accetta. Il percorso può avere i segnaposto {giocatore}, {squadra}, {lega} e {tappa}: il test
     * li sostituisce con gli id veri della fixture o, per il 404, con id che non esistono.
     */
    record Endpoint(HttpMethod metodo, String percorso, Object corpo) {
        @Override
        public String toString() {
            return metodo + " " + percorso;
        }
    }

    /** Gli endpoint che agiscono su una risorsa di qualcuno: il proprietario (o un ADMIN) sì, gli altri no */
    static Stream<Endpoint> endpointSuUnaRisorsa() {
        return Stream.of(
                new Endpoint(PUT, "/api/anagrafe/giocatori/{giocatore}", Map.of("nome", "Luca", "cognome", "Neri")),
                new Endpoint(DELETE, "/api/anagrafe/giocatori/{giocatore}", null),
                new Endpoint(PUT, "/api/anagrafe/squadre/{squadra}", Map.of("nome", "Lupi")),
                new Endpoint(DELETE, "/api/anagrafe/squadre/{squadra}", null),
                new Endpoint(GET, "/api/leghe/{lega}", null),
                new Endpoint(PATCH, "/api/leghe/{lega}", Map.of("nome", "Circuito 2027")),
                new Endpoint(DELETE, "/api/leghe/{lega}", null),
                new Endpoint(POST, "/api/leghe/{lega}/tappe", TappaDiProva.tappa().build()),
                // La versione 0 è quella della tappa della fixture; l'id del corpo non conta, vale quello del percorso
                new Endpoint(PUT, "/api/tappe/{tappa}", TappaDiProva.tappa().versione(0L).build()),
                new Endpoint(DELETE, "/api/tappe/{tappa}", null),
                new Endpoint(PUT, "/api/archivio/{tappa}", null),
                new Endpoint(DELETE, "/api/archivio/{tappa}", null));
    }

    /** Gli altri endpoint che chiedono un account: non riguardano una risorsa già esistente */
    static Stream<Endpoint> altriEndpointProtetti() {
        return Stream.of(
                new Endpoint(GET, "/api/auth/me", null),
                new Endpoint(GET, "/api/utenti", null),
                new Endpoint(GET, "/api/leghe", null),
                new Endpoint(POST, "/api/leghe", Map.of("nome", "Circuito 2027")),
                new Endpoint(POST, "/api/anagrafe/giocatori", Map.of("nome", "Luca", "cognome", "Neri")),
                new Endpoint(POST, "/api/anagrafe/squadre", Map.of("nome", "Lupi")),
                new Endpoint(GET, "/api/coach/status", null),
                new Endpoint(POST, "/api/coach/chat", Map.of("messages", List.of())));
    }

    static Stream<Endpoint> endpointProtetti() {
        return Stream.concat(endpointSuUnaRisorsa(), altriEndpointProtetti());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JWTtools jwt;
    @Autowired UtenteRepository utenti;
    @Autowired AnagrafeService anagrafeService;
    @Autowired LegaService legaService;
    @Autowired ArchivioService archivioService;
    @Autowired JdbcTemplate jdbc;

    private Utente mario; // proprietario di tutto ciò che c'è nel database
    private Utente luigi; // un altro utente
    private Utente admin;
    private Mondo cose; // le cose di Mario

    @BeforeEach
    void creaLaFixture() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));
        admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));
        cose = new MondoDiProva(anagrafeService, legaService, archivioService).crea(mario);
    }

    /* ── 401: senza token nessun endpoint protetto risponde ── */

    @ParameterizedTest
    @MethodSource("endpointProtetti")
    void senzaToken_risponde401ENonCambiaNulla(Endpoint endpoint) throws Exception {
        List<String> prima = fotografia();

        invia(endpoint, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(fotografia()).isEqualTo(prima);
    }

    /* ── 403 e 404: chi non è il proprietario e la risorsa che non c'è ── */

    @ParameterizedTest
    @MethodSource("endpointSuUnaRisorsa")
    void chiNonEProprietario_risponde403ENonCambiaNulla(Endpoint endpoint) throws Exception {
        List<String> prima = fotografia();

        invia(endpoint, luigi).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(startsWith("Solo chi ha creato")))
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(fotografia()).isEqualTo(prima);
    }

    @ParameterizedTest
    @MethodSource("endpointSuUnaRisorsa")
    void unaRisorsaCheNonEsiste_risponde404(Endpoint endpoint) throws Exception {
        List<String> prima = fotografia();
        // Gli id dei segnaposto non esistono: l'utente è il proprietario di tutto il resto, quindi manca solo la risorsa
        String percorso = conId(endpoint.percorso(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        mvc.perform(richiesta(endpoint.metodo(), percorso, endpoint.corpo(), mario)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(fotografia()).isEqualTo(prima);
    }

    @ParameterizedTest
    @MethodSource("endpointSuUnaRisorsa")
    void ilProprietarioRiesce(Endpoint endpoint) throws Exception {
        invia(endpoint, mario).andExpect(status().is2xxSuccessful());
    }

    // Un ADMIN passa il controllo di proprietà anche sui dati di un altro (AccessGuard), e se scrive lascia una riga di log
    @ParameterizedTest
    @MethodSource("endpointSuUnaRisorsa")
    void unAdminRiesceSuiDatiDiUnAltro_eSeScriveLasciaLaRigaDiIntervento(Endpoint endpoint) throws Exception {
        try (LogCatturato log = new LogCatturato(AccessGuard.class)) {
            invia(endpoint, admin).andExpect(status().is2xxSuccessful());

            // La lettura non è un intervento: la riga c'è solo per le scritture
            int righeAttese = 1;
            if (endpoint.metodo() == GET) righeAttese = 0;
            assertThat(log.righe()).hasSize(righeAttese);
        }
    }

    /* ── Gli endpoint senza risorsa: che cosa risponde chi ha il permesso ── */

    @Test
    void meRispondeConLUtenteDelToken_senzaPassword() throws Exception {
        invia(new Endpoint(GET, "/api/auth/me", null), mario).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("mario@test.it"))
                .andExpect(jsonPath("$.name").value("Mario"))
                .andExpect(jsonPath("$.ruolo").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    // L'elenco degli utenti è dell'ADMIN: chi lo legge vede tutti, e nessuno con la password
    @Test
    void unAdminLeggeLElencoDegliUtenti_senzaLePassword() throws Exception {
        invia(new Endpoint(GET, "/api/utenti", null), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].email", hasItem("luigi@test.it")))
                .andExpect(jsonPath("$[*].password").isEmpty());
    }

    /* ── Richieste ── */

    /** La richiesta dell'endpoint sulle risorse della fixture, di `chi` (null: senza token) */
    private ResultActions invia(Endpoint endpoint, Utente chi) throws Exception {
        String percorso = conId(endpoint.percorso(), cose.giocatore(), cose.squadra(), cose.lega(), cose.tappe().getFirst());
        return mvc.perform(richiesta(endpoint.metodo(), percorso, endpoint.corpo(), chi));
    }

    /** Il percorso con gli id al posto dei segnaposto */
    private static String conId(String percorso, UUID giocatore, UUID squadra, UUID lega, UUID tappa) {
        return percorso
                .replace("{giocatore}", giocatore.toString())
                .replace("{squadra}", squadra.toString())
                .replace("{lega}", lega.toString())
                .replace("{tappa}", tappa.toString());
    }

    /** Il token c'è solo se `chi` non è null, e il corpo solo se l'endpoint ne ha uno */
    private MockHttpServletRequestBuilder richiesta(HttpMethod metodo, String percorso, Object corpo, Utente chi) {
        MockHttpServletRequestBuilder richiesta = request(metodo, percorso);
        if (chi != null) {
            richiesta.header(AUTHORIZATION, "Bearer " + jwt.generateToken(chi));
        }
        if (corpo != null) {
            richiesta.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo));
        }
        return richiesta;
    }

    /**
     * Tutto ciò che una richiesta rifiutata potrebbe aver cambiato, una riga per elemento: nome, versione e data di modifica di
     * ogni lega, tappa, giocatore e squadra, le righe dei roster e le pubblicazioni. Dopo un rifiuto la fotografia è la stessa.
     */
    private List<String> fotografia() {
        return jdbc.queryForList("""
                select 'lega ' || id || ' ' || nome || ' ' || modificato_il from leghe
                union all select 'tappa ' || id || ' ' || nome || ' ' || versione || ' ' || modificato_il from tappe
                union all select 'giocatore ' || id || ' ' || nome || ' ' || cognome || ' ' || modificato_il from anagrafe_giocatori
                union all select 'squadra ' || id || ' ' || nome || ' ' || modificato_il from anagrafe_squadre
                union all select 'roster ' || squadra_id || ' ' || giocatore_id || ' ' || posizione from anagrafe_squadre_roster
                union all select 'archivio ' || tappa_id || ' ' || pubblicato_il from archivio_tappe
                order by 1
                """, String.class);
    }
}
