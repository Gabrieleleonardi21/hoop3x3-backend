package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.Hoop3x3BackendApplication;
import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.MondoDiProva;
import com.hoop3x3.backend.MondoDiProva.Mondo;
import com.hoop3x3.backend.TappaDiProva;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.services.AccessGuard;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.LegaService;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * LogApplicativiTest. Un endpoint nuovo si aggiunge a una delle liste qui sotto (protetti o pubblici): se manca, cade
 * ogniEndpointDellApplicazioneStaInUnaDelleListe.
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

    /**
     * Un endpoint pubblico, lo stato che risponde a chi non ha un token e, se è un errore con un testo fisso, il messaggio:
     * senza il testo un 401 del controller e quello della catena di sicurezza (stesso formato) sarebbero indistinguibili.
     * `messaggio` è null quando non c'è niente da controllare.
     */
    record Pubblico(Endpoint endpoint, int statoSenzaToken, String messaggio) {
        Pubblico(Endpoint endpoint, int statoSenzaToken) {
            this(endpoint, statoSenzaToken, null);
        }

        @Override
        public String toString() {
            return endpoint + " -> " + statoSenzaToken;
        }
    }

    /**
     * Gli endpoint che non chiedono un account, dichiarati a mano: la regola è in SecurityConfig, e un endpoint nuovo sotto un
     * percorso già pubblico (le GET di anagrafe e archivio) lo diventerebbe senza che nessuno lo decida. Qui chi lo aggiunge
     * deve dire che è pubblico, o metterlo tra i protetti (e allora il 401 lo prova).
     */
    static Stream<Pubblico> endpointPubblici() {
        return Stream.of(
                // Senza un corpo valido il controller risponde 400: la sicurezza ha lasciato passare
                new Pubblico(new Endpoint(POST, "/api/auth/register", Map.of()), 400),
                new Pubblico(new Endpoint(POST, "/api/auth/login", Map.of()), 400),
                // Rinnovo e uscita si autenticano con il cookie, non con il token: il 401 senza cookie è quello del controller, che ha
                // il suo messaggio (quello della catena di sicurezza è «Autenticazione richiesta: accedi per continuare»)
                new Pubblico(new Endpoint(POST, "/api/auth/refresh", null), 401, "Sessione scaduta: accedi di nuovo"),
                new Pubblico(new Endpoint(POST, "/api/auth/logout", null), 204),
                new Pubblico(new Endpoint(GET, "/api/anagrafe/giocatori", null), 200),
                new Pubblico(new Endpoint(GET, "/api/anagrafe/squadre", null), 200),
                new Pubblico(new Endpoint(GET, "/api/archivio", null), 200),
                new Pubblico(new Endpoint(GET, "/api/archivio/{tappa}", null), 200));
    }

    @Autowired MockMvc mvc;
    // Le mappature di Spring MVC: l'elenco vero degli endpoint dell'applicazione
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappature;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTools jwt;
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

    /* ── L'elenco è completo: ogni endpoint dell'applicazione ha le sue regole provate qui sotto ── */

    // Senza questo test un endpoint nuovo potrebbe nascere senza il suo 401, 403 e 404: nessuno lo vedrebbe finché non lo prova
    // qualcuno. Confronta le mappature vere dei controller con le due liste: manca o è in più, il messaggio dice quale
    @Test
    void ogniEndpointDellApplicazioneStaInUnaDelleListe() {
        Set<String> elencati = Stream.concat(endpointProtetti(), endpointPubblici().map(Pubblico::endpoint))
                .map(AccessoEndpointIT::chiave)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(endpointDellApplicazione()).as("endpoint dei controller, contro quelli elencati in endpointProtetti e endpointPubblici")
                .containsExactlyInAnyOrderElementsOf(elencati);
    }

    // Un endpoint protetto con una variabile nel percorso agisce su una risorsa di qualcuno: ha bisogno del 403 e del 404, che
    // provano solo gli endpoint di endpointSuUnaRisorsa. Messo in altriEndpointProtetti avrebbe il solo 401 senza che nessuno se ne accorga
    @Test
    void ogniEndpointProtettoConUnaVariabileDiPercorsoStaTraQuelliSuUnaRisorsa() {
        Set<String> pubblici = endpointPubblici().map(pubblico -> chiave(pubblico.endpoint())).collect(Collectors.toSet());
        Set<String> suUnaRisorsa = endpointSuUnaRisorsa().map(AccessoEndpointIT::chiave).collect(Collectors.toSet());
        Set<String> protettiConUnaVariabile = endpointDellApplicazione().stream()
                .filter(endpoint -> endpoint.contains("{}") && !pubblici.contains(endpoint))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(suUnaRisorsa).as("endpoint protetti con una variabile di percorso, che vanno in endpointSuUnaRisorsa")
                .containsAll(protettiConUnaVariabile);
    }

    // I tetti dei campi (CampiDiTesto) valgono solo se l'endpoint che riceve il corpo lo valida: ValidazioneWebTest lo prova su un
    // endpoint per DTO, questo su tutti. Un parametro @RequestBody senza @Validated (o @Valid) lascerebbe passare qualsiasi corpo
    // fino al servizio e al database, e nessun altro test se ne accorgerebbe
    @Test
    void ogniCorpoDiUnaRichiestaVieneValidato() {
        List<String> senzaValidazione = new ArrayList<>();
        int conUnCorpo = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> handler : handlerDellApplicazione().entrySet()) {
            for (MethodParameter parametro : handler.getValue().getMethodParameters()) {
                if (!parametro.hasParameterAnnotation(RequestBody.class)) continue;
                conUnCorpo++;
                if (!parametro.hasParameterAnnotation(Validated.class) && !parametro.hasParameterAnnotation(Valid.class)) {
                    senzaValidazione.add(handler.getValue().getBeanType().getSimpleName() + "." + handler.getValue().getMethod().getName()
                            + " " + handler.getKey());
                }
            }
        }

        assertThat(conUnCorpo).as("endpoint con un corpo trovati: se è zero il test non guarda niente").isPositive();
        assertThat(senzaValidazione).as("endpoint con un @RequestBody senza @Validated o @Valid").isEmpty();
    }

    /**
     * Gli handler dell'applicazione, dalle mappature vere di Spring MVC: quelli il cui tipo sta sotto il pacchetto dell'applicazione,
     * anche in un sottopacchetto, così un controller futuro non sfugge all'audit. Restano fuori quelli di Spring Boot (il
     * controller dell'/error).
     */
    private Map<RequestMappingInfo, HandlerMethod> handlerDellApplicazione() {
        String pacchettoDellApplicazione = Hoop3x3BackendApplication.class.getPackageName() + ".";
        return mappature.getHandlerMethods().entrySet().stream()
                .filter(handler -> handler.getValue().getBeanType().getName().startsWith(pacchettoDellApplicazione))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Metodo e percorso («GET /api/leghe/{}») di ogni endpoint dell'applicazione */
    private Set<String> endpointDellApplicazione() {
        Set<String> endpoint = new TreeSet<>();
        handlerDellApplicazione().keySet().forEach(mappatura -> {
            Set<RequestMethod> metodiHttp = mappatura.getMethodsCondition().getMethods();
            for (String percorso : mappatura.getPathPatternsCondition().getPatternValues()) {
                if (metodiHttp.isEmpty()) endpoint.add("QUALSIASI " + senzaNomiDeiSegnaposto(percorso));
                for (RequestMethod metodoHttp : metodiHttp) {
                    endpoint.add(metodoHttp + " " + senzaNomiDeiSegnaposto(percorso));
                }
            }
        });
        return endpoint;
    }

    /** Come si presenta un endpoint elencato, nella stessa forma di endpointDellApplicazione */
    private static String chiave(Endpoint endpoint) {
        return endpoint.metodo() + " " + senzaNomiDeiSegnaposto(endpoint.percorso());
    }

    // La lista dei pubblici non è solo una dichiarazione: senza token questi endpoint rispondono davvero, e non con un 401 della
    // sicurezza (dove c'è un errore con un testo fisso, il testo dice chi ha risposto)
    @ParameterizedTest
    @MethodSource("endpointPubblici")
    void gliEndpointPubbliciRispondonoSenzaToken(Pubblico pubblico) throws Exception {
        ResultActions risposta = invia(pubblico.endpoint(), null).andExpect(status().is(pubblico.statoSenzaToken()));

        if (pubblico.messaggio() != null) {
            risposta.andExpect(jsonPath("$.message").value(pubblico.messaggio()));
        }
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

    /** «/api/leghe/{id}» e «/api/leghe/{lega}» sono lo stesso percorso: si confrontano senza i nomi dei segnaposto */
    private static String senzaNomiDeiSegnaposto(String percorso) {
        return percorso.replaceAll("\\{[^}]+}", "{}");
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
