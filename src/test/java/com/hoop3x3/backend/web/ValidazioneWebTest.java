package com.hoop3x3.backend.web;

import com.hoop3x3.backend.CampiDiTesto;
import com.hoop3x3.backend.controllers.AnagrafeController;
import com.hoop3x3.backend.controllers.ArchivioController;
import com.hoop3x3.backend.controllers.AuthController;
import com.hoop3x3.backend.controllers.CampettoController;
import com.hoop3x3.backend.controllers.CoachController;
import com.hoop3x3.backend.controllers.LegaController;
import com.hoop3x3.backend.controllers.TappaController;
import com.hoop3x3.backend.dto.CampettoRequestDTO;
import com.hoop3x3.backend.dto.GiocatoreRequestDTO;
import com.hoop3x3.backend.dto.LoginRequestDTO;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.PatchLegaDTO;
import com.hoop3x3.backend.dto.RegisterRequestDTO;
import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.LimiteDimensioneFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.CampettoService;
import com.hoop3x3.backend.services.CoachAiService;
import com.hoop3x3.backend.services.LegaService;
import com.hoop3x3.backend.services.RefreshTokenService;
import com.hoop3x3.backend.services.UtenteService;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validazione d'ingresso a livello web: ciò che non entra nelle colonne del database o non ha la forma attesa
 * risponde 400 con il nome del campo. I servizi sono simulati e ogni caso rifiutato verifica che nessuno sia stato
 * chiamato: sono l'unica strada verso il database, quindi una richiesta rifiutata qui non lo raggiunge.
 */
@WebMvcTest(controllers = {LegaController.class, TappaController.class, AnagrafeController.class,
        ArchivioController.class, AuthController.class, CoachController.class, CampettoController.class})
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JsonAuthEntryPoint.class, AuthCookies.class,
        ExceptionsHandler.class, LimiteDimensioneFilter.class, UtenteService.class})
// I login e le registrazioni di questa classe partono tutti dallo stesso indirizzo e sono quasi quanti ne ammette il limite di
// produzione (10 al minuto): un caso in più rischierebbe un 429 che non c'entra (stesso valore di LogApplicativiTest)
@TestPropertySource(properties = "limite.auth-al-minuto=100000")
class ValidazioneWebTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean LegaService legaService;
    @MockitoBean AnagrafeService anagrafeService;
    @MockitoBean ArchivioService archivioService;
    @MockitoBean CampettoService campettoService;
    @MockitoBean CoachAiService coachAiService;
    @MockitoBean RefreshTokenService refreshTokenService;
    @MockitoBean AuthenticationManager authenticationManager;
    @MockitoBean JwtTools jwtTools;
    @MockitoBean UtenteRepository utenteRepository;

    private static final int TRE_MB = 3 * 1024 * 1024; // oltre il limite di 2 MB del filtro

    private final Utente mario = new Utente("mario@x.it", "hash", "Mario", Ruolo.USER);

    // Login e registrazione che riescono: servono ai casi «al limite», che devono superare la validazione ed entrare nel controller
    @BeforeEach
    void accessoRiuscito() {
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(mario, null, mario.getAuthorities()));
        when(utenteRepository.save(any(Utente.class))).thenAnswer(chiamata -> chiamata.getArgument(0));
        when(refreshTokenService.emetti(any())).thenReturn("refresh");
        when(jwtTools.generateToken(any())).thenReturn("jwt");
    }

    /* ── Corpi validi: i test cambiano solo il campo che vogliono mettere alla prova ── */

    private static Map<String, Object> tappa() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", UUID.randomUUID());
        t.put("nome", "Tappa di Roma");
        t.put("luogo", "Roma");
        t.put("data", "2026-06-14");
        t.put("nGironi", 1);
        t.put("regole", Map.of("target", 21, "durata", 10, "ot", 2, "shot", 12));
        t.put("squadre", List.of());
        t.put("partite", List.of());
        return t;
    }

    private static Map<String, Object> nuovaLega() {
        return new LinkedHashMap<>(Map.of("nome", "Circuito 2026"));
    }

    private static Map<String, Object> giocatore() {
        return new LinkedHashMap<>(Map.of("nome", "Mario", "cognome", "Rossi"));
    }

    private static Map<String, Object> squadra() {
        return new LinkedHashMap<>(Map.of("nome", "Roma 3x3"));
    }

    private static Map<String, Object> campetto() {
        return new LinkedHashMap<>(Map.of("nome", "Parco Dora", "lat", 45.08972, "lng", 7.66669, "superficie", "Sintetico",
                "canestri", 4, "stato", "buono"));
    }

    private static Map<String, Object> accesso(String email) {
        return new LinkedHashMap<>(Map.of("email", email, "password", "password-valida"));
    }

    private static Map<String, Object> registrazione(String email) {
        return new LinkedHashMap<>(Map.of("name", "Mario", "email", email, "password", "password-valida"));
    }

    private static List<Map<String, Object>> tappeDiverse(int quante) {
        List<Map<String, Object>> tappe = new ArrayList<>();
        for (int i = 0; i < quante; i++) tappe.add(tappa()); // ogni tappa ha un id casuale diverso
        return tappe;
    }

    private static List<UUID> idDiversi(int quanti) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < quanti; i++) ids.add(UUID.randomUUID());
        return ids;
    }

    private static String urlNuovaTappa() {
        return "/api/leghe/" + UUID.randomUUID() + "/tappe";
    }

    /** Email valida per @Email (parte locale di 64 caratteri, etichette del dominio di al massimo 63) lunga esattamente `lunghezza` */
    private static String emailDi(int lunghezza) {
        int ultimaEtichetta = lunghezza - (64 + 1 + 63 + 1 + 63 + 1);
        return "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(ultimaEtichetta);
    }

    /* ── Invio e verifica ── */

    private ResultActions invia(HttpMethod metodo, String url, Object corpo) throws Exception {
        return mvc.perform(request(metodo, url).with(user(mario)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(corpo)));
    }

    /** 400 con il nome del campo nel messaggio, e nessun servizio (quindi nessun database) chiamato */
    private void rifiutata(HttpMethod metodo, String url, Object corpo, String campo) throws Exception {
        rifiutataConMessaggio(metodo, url, corpo, containsString(campo + ":"));
    }

    /** Come rifiutata, ma il messaggio d'errore deve soddisfare `messaggio` */
    private void rifiutataConMessaggio(HttpMethod metodo, String url, Object corpo, Matcher<String> messaggio) throws Exception {
        corpoStandard(invia(metodo, url, corpo).andExpect(status().isBadRequest()))
                .andExpect(jsonPath("$.message", messaggio));
        verifyNoInteractions(legaService, anagrafeService, archivioService, campettoService, coachAiService, authenticationManager,
                refreshTokenService, utenteRepository);
    }

    /** Il corpo d'errore è solo {message, timestamp}, con il timestamp nel formato ISO di ExceptionsHandler (LocalDateTime) */
    private static ResultActions corpoStandard(ResultActions esito) throws Exception {
        return esito
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.timestamp", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?")));
    }

    /** La validazione lascia passare la richiesta: arriva al controller e risponde 2xx */
    private void accettata(HttpMethod metodo, String url, Object corpo) throws Exception {
        invia(metodo, url, corpo).andExpect(status().is2xxSuccessful());
    }

    /* ── La data della tappa: vuota o ISO. I tetti dei campi di testo sono più sotto, nella tabella CampiDiTesto ── */

    // La colonna è da 10 caratteri e contiene una data ISO o niente (è il valore dell'input date del frontend)
    @ParameterizedTest
    @ValueSource(strings = {"2026-6-1", "01/06/2026", "2026-06-011", "domani", " "})
    void dataDellaTappaNonIso_risponde400ConIlCampo(String data) throws Exception {
        Map<String, Object> t = tappa();
        t.put("data", data);

        rifiutata(POST, urlNuovaTappa(), t, "data");
    }

    @Test
    void dataDellaTappaVuotaOAssente_siAccetta() throws Exception {
        Map<String, Object> t = tappa();

        t.put("data", ""); // data vuota: la tappa non ha ancora una data
        accettata(PUT, "/api/tappe/" + UUID.randomUUID(), t);

        t.remove("data"); // data assente
        accettata(PUT, "/api/tappe/" + UUID.randomUUID(), t);
    }

    /* ── La lingua dei messaggi è fissa: italiano, qualunque Accept-Language mandi il browser ── */

    // I messaggi senza un testo proprio (@NotBlank) li traduce Hibernate Validator nella lingua della richiesta: con un browser
    // in inglese arrivava «must not be blank», mentre il README promette messaggi in italiano. Il locale è fisso (spring.mvc.locale)
    @ParameterizedTest
    @ValueSource(strings = {"en", "en-US,en;q=0.9", "de"})
    void messaggioDiValidazione_conAcceptLanguageStraniero_restaInItaliano(String lingua) throws Exception {
        Map<String, Object> lega = nuovaLega();
        lega.put("nome", "");

        mvc.perform(post("/api/leghe").with(user(mario)).header(HttpHeaders.ACCEPT_LANGUAGE, lingua)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(lega)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", allOf(containsString("nome: non deve essere"), not(containsString("must not")))));
    }

    /* ── Campi obbligatori: ogni endpoint che legge un corpo li pretende ── */

    /** Gli endpoint con un corpo, il corpo e i campi che non possono mancare (i nomi nel messaggio del 400) */
    static Stream<Arguments> endpointConCampiObbligatori() {
        // La tappa ha anche nGironi, un int: senza, Jackson rifiuta il corpo prima della validazione (vedi il test qui sotto)
        Map<String, Object> soloGironi = Map.of("nGironi", 1);
        List<String> campiDellaTappa = List.of("id", "nome", "regole", "squadre", "partite");
        return Stream.of(
                arguments(POST, "/api/leghe", Map.of(), List.of("nome")),
                arguments(PATCH, "/api/leghe/" + UUID.randomUUID(), Map.of(), List.of("nome")),
                arguments(POST, urlNuovaTappa(), soloGironi, campiDellaTappa),
                arguments(PUT, "/api/tappe/" + UUID.randomUUID(), soloGironi, campiDellaTappa),
                arguments(POST, "/api/anagrafe/giocatori", Map.of(), List.of("nome", "cognome")),
                arguments(PUT, "/api/anagrafe/giocatori/" + UUID.randomUUID(), Map.of(), List.of("nome", "cognome")),
                arguments(POST, "/api/anagrafe/squadre", Map.of(), List.of("nome")),
                arguments(PUT, "/api/anagrafe/squadre/" + UUID.randomUUID(), Map.of(), List.of("nome")),
                arguments(POST, "/api/campetti", Map.of(), List.of("nome", "lat", "lng", "superficie", "canestri", "stato")),
                arguments(PUT, "/api/campetti/" + UUID.randomUUID(), Map.of(), List.of("nome", "lat", "lng", "superficie", "canestri", "stato")),
                arguments(POST, "/api/auth/login", Map.of(), List.of("email", "password")),
                arguments(POST, "/api/auth/register", Map.of(), List.of("name", "email", "password")),
                arguments(POST, "/api/coach/chat", Map.of(), List.of("messages")));
    }

    // Un corpo senza i campi obbligatori: il messaggio li nomina tutti. Il nome si cerca per intero: «cognome:» non deve far
    // risultare presente «nome»
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpointConCampiObbligatori")
    void corpoSenzaCampiObbligatori_risponde400NominandoliTutti(HttpMethod metodo, String url, Map<String, Object> corpo,
                                                                List<String> campi) throws Exception {
        List<Matcher<? super String>> nominati = campi.stream()
                .<Matcher<? super String>>map(campo -> matchesPattern("(?s)(.*, )?" + Pattern.quote(campo) + ": .*"))
                .toList();

        rifiutataConMessaggio(metodo, url, corpo, allOf(nominati));
    }

    // Anche nGironi è obbligatorio, ma è un int: senza il campo Jackson non riesce a costruire il DTO e il 400 ha il messaggio del
    // corpo illeggibile, non quello di un campo
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("scrittureDellaTappa")
    void tappaSenzaNGironi_risponde400ComeCorpoNonLeggibile(HttpMethod metodo, String url) throws Exception {
        Map<String, Object> t = tappa();
        t.remove("nGironi");

        rifiutataConMessaggio(metodo, url, t, is("Corpo della richiesta non valido"));
    }

    static Stream<Arguments> scrittureDellaTappa() {
        return Stream.of(arguments(POST, urlNuovaTappa()), arguments(PUT, "/api/tappe/" + UUID.randomUUID()));
    }

    /* ── Tetti dei campi di testo: la tabella è CampiDiTesto, e LimitiColonneIT la confronta con le colonne del database ── */

    /** Come si scrive ciò che porta un DTO: metodo, indirizzo e un corpo valido, nuovo a ogni richiesta */
    private record Scrittura(HttpMethod metodo, String url, Supplier<Map<String, Object>> corpo) {}

    private static final Map<Class<?>, Scrittura> SCRITTURE_PER_DTO = Map.of(
            NuovaLegaDTO.class, new Scrittura(POST, "/api/leghe", ValidazioneWebTest::nuovaLega),
            // La rinomina porta lo stesso corpo di una lega nuova: il solo nome
            PatchLegaDTO.class, new Scrittura(PATCH, "/api/leghe/" + UUID.randomUUID(), ValidazioneWebTest::nuovaLega),
            TappaDTO.class, new Scrittura(POST, urlNuovaTappa(), ValidazioneWebTest::tappa),
            GiocatoreRequestDTO.class, new Scrittura(POST, "/api/anagrafe/giocatori", ValidazioneWebTest::giocatore),
            SquadraRequestDTO.class, new Scrittura(POST, "/api/anagrafe/squadre", ValidazioneWebTest::squadra),
            CampettoRequestDTO.class, new Scrittura(POST, "/api/campetti", ValidazioneWebTest::campetto),
            RegisterRequestDTO.class, new Scrittura(POST, "/api/auth/register", () -> registrazione("mario@x.it")),
            LoginRequestDTO.class, new Scrittura(POST, "/api/auth/login", () -> accesso("mario@x.it")));

    /** Ogni campo della tabella con l'endpoint che lo riceve, un corpo valido e il suo tetto (letto dal DTO) */
    static Stream<Arguments> campiConUnTetto() {
        return CampiDiTesto.TUTTI.stream().map(campo -> {
            Scrittura scrittura = SCRITTURE_PER_DTO.get(campo.dto());
            if (scrittura == null) throw new IllegalStateException("Manca l'endpoint che riceve " + campo);
            return arguments(campo.toString(), scrittura.metodo(), scrittura.url(), scrittura.corpo(), campo.componente(), campo.tetto());
        });
    }

    @ParameterizedTest(name = "{0}: al massimo {5} caratteri")
    @MethodSource("campiConUnTetto")
    void testoAlTettoDelDto_siAccetta(String etichetta, HttpMethod metodo, String url, Supplier<Map<String, Object>> corpo,
                                      String campo, int tetto) throws Exception {
        Map<String, Object> richiesta = corpo.get();
        richiesta.put(campo, testoDi(campo, tetto));

        accettata(metodo, url, richiesta);
    }

    @ParameterizedTest(name = "{0}: oltre {5} caratteri")
    @MethodSource("campiConUnTetto")
    void testoOltreIlTettoDelDto_risponde400ConIlCampo(String etichetta, HttpMethod metodo, String url,
                                                       Supplier<Map<String, Object>> corpo, String campo, int tetto) throws Exception {
        Map<String, Object> richiesta = corpo.get();
        richiesta.put(campo, testoDi(campo, tetto + 1));

        rifiutata(metodo, url, richiesta, campo);
    }

    // Il nome della registrazione ha anche un minimo: un carattere solo non basta
    @Test
    void nomeDellaRegistrazioneDiUnCarattere_risponde400ConIlCampo() throws Exception {
        Map<String, Object> r = registrazione("mario@x.it");
        r.put("name", "x");

        rifiutata(POST, "/api/auth/register", r, "name");
    }

    /**
     * Un testo lungo `lunghezza` caratteri: un'email deve restare un'email valida e un indirizzo web un indirizzo valido,
     * altrimenti il 400 non sarebbe per la lunghezza
     */
    private static String testoDi(String campo, int lunghezza) {
        if (campo.equals("email")) return emailDi(lunghezza);
        if (INDIRIZZI_WEB.contains(campo)) return "https://" + "x".repeat(lunghezza - "https://".length());
        return "x".repeat(lunghezza);
    }

    /* ── Indirizzi web: vuoti, http(s) o un percorso del sito, altrimenti 400 (validation/IndirizzoWeb) ── */

    /** I campi della squadra che sono indirizzi web */
    private static final List<String> INDIRIZZI_WEB = List.of("logo", "website", "instagram");

    static Stream<Arguments> indirizziNonValidi() {
        return INDIRIZZI_WEB.stream().flatMap(campo -> Stream.of("javascript:alert(1)", "ftp://roma3x3.it", "www.roma3x3.it",
                "//evil.example/x").map(indirizzo -> arguments(campo, indirizzo)));
    }

    // Il frontend mette questi campi in src e href: uno schema diverso da http(s) risponde 400 con il nome del campo e un
    // messaggio in italiano, e non arriva al servizio
    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("indirizziNonValidi")
    void indirizzoWebDellaSquadraNonValido_risponde400ConIlCampo(String campo, String indirizzo) throws Exception {
        Map<String, Object> s = squadra();
        s.put(campo, indirizzo);

        rifiutataConMessaggio(POST, "/api/anagrafe/squadre", s,
                allOf(containsString(campo + ": "), containsString("http:// o https://")));
    }

    // Vuoti, assenti, http(s) e i loghi integrati del frontend (/logos/nome.svg) passano
    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "https://roma3x3.it", "http://roma3x3.it/logo.png", "/logos/roma.svg"})
    void indirizzoWebDellaSquadraValido_siAccetta(String indirizzo) throws Exception {
        Map<String, Object> s = squadra();
        for (String campo : INDIRIZZI_WEB) s.put(campo, indirizzo);

        accettata(POST, "/api/anagrafe/squadre", s);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("scrittureDellaTappa")
    void urlDiUnVideoNonValido_risponde400NominandoIlVideo(HttpMethod metodo, String url) throws Exception {
        Map<String, Object> t = tappa();
        t.put("video", List.of(Map.of("id", "v1", "titolo", "Finale", "url", "https://youtu.be/x"),
                Map.of("id", "v2", "titolo", "Highlights", "url", "javascript:alert(1)")));

        rifiutataConMessaggio(metodo, url, t, allOf(containsString("video: il video n. 2"), containsString("http:// o https://")));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("scrittureDellaTappa")
    void urlDiUnVideoTroppoLungo_risponde400(HttpMethod metodo, String url) throws Exception {
        Map<String, Object> t = tappa();
        t.put("video", List.of(Map.of("id", "v1", "titolo", "Finale", "url", "https://" + "x".repeat(2041))));

        rifiutataConMessaggio(metodo, url, t, containsString("video: il video n. 1"));
    }

    // Video con url http(s), senza url o con url vuoto: tutti validi, e anche nessun blocco video
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("scrittureDellaTappa")
    void videoConUrlValidiOSenzaUrl_siAccettano(HttpMethod metodo, String url) throws Exception {
        Map<String, Object> t = tappa();
        t.put("video", List.of(Map.of("id", "v1", "titolo", "Finale", "url", "https://youtu.be/x"),
                Map.of("id", "v2", "titolo", "Senza url"), Map.of("id", "v3", "titolo", "Url vuoto", "url", "")));
        accettata(metodo, url, t);

        t.remove("video");
        accettata(metodo, url, t);
    }

    /* ── Pubblicazione in archivio: il server non legge nessun corpo ── */

    // La tappa la carica il server dal database: PUT /api/archivio/{tappaId} non ha corpo
    @Test
    void pubblicazioneSenzaCorpo_arrivaAlServizioConLIdDelPercorso() throws Exception {
        UUID tappaId = UUID.randomUUID();

        mvc.perform(put("/api/archivio/" + tappaId).with(user(mario))).andExpect(status().isOk());

        verify(archivioService).pubblica(mario, tappaId);
    }

    // Un corpo eventuale si ignora (non si legge né si valida): con il vecchio DTO un {tappa, lega} non valido, un JSON
    // malformato o un testo qualsiasi sarebbero stati un 400, ora la risposta non cambia
    @ParameterizedTest
    @ValueSource(strings = {"{\"tappa\":{\"nome\":\"\"},\"lega\":\"\"}", "{\"tappa\":null}", "{nome:", "non e JSON"})
    void pubblicazioneConUnCorpoQualsiasi_ilCorpoSiIgnora(String corpo) throws Exception {
        UUID tappaId = UUID.randomUUID();

        mvc.perform(put("/api/archivio/" + tappaId).with(user(mario)).contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk());

        verify(archivioService).pubblica(mario, tappaId);
    }

    /* ── Liste: elementi non nulli, lunghezza massima e id di tappa unici ── */

    @Test
    void importConTappaNull_risponde400ConIlCampo() throws Exception {
        Map<String, Object> lega = nuovaLega();
        lega.put("tappe", Collections.singletonList(null)); // tappe: [null]

        rifiutata(POST, "/api/leghe", lega, "tappe[0]");
    }

    @Test
    void importConTappaNonValida_nominaIlPercorsoDelCampo() throws Exception {
        Map<String, Object> tappaTroppoLunga = tappa();
        tappaTroppoLunga.put("nome", "x".repeat(121));
        Map<String, Object> lega = nuovaLega();
        lega.put("tappe", List.of(tappaTroppoLunga));

        rifiutata(POST, "/api/leghe", lega, "tappe[0].nome");
    }

    @Test
    void importConPiuDi100Tappe_risponde400ConIlCampo() throws Exception {
        Map<String, Object> lega = nuovaLega();
        lega.put("tappe", tappeDiverse(101));

        rifiutata(POST, "/api/leghe", lega, "tappe");
    }

    @Test
    void importConIdDiTappaDuplicati_risponde400() throws Exception {
        Map<String, Object> prima = tappa();
        Map<String, Object> seconda = tappa();
        seconda.put("id", prima.get("id")); // stesso id nello stesso import
        Map<String, Object> lega = nuovaLega();
        lega.put("tappe", List.of(prima, seconda));

        rifiutata(POST, "/api/leghe", lega, "tappeConIdUnici");
    }

    @Test
    void importAlLimiteDelleListe_siAccetta() throws Exception {
        Map<String, Object> lega = nuovaLega();
        lega.put("tappe", tappeDiverse(100));

        accettata(POST, "/api/leghe", lega);
    }

    @Test
    void rosterConGiocatoreNull_risponde400ConIlCampo() throws Exception {
        Map<String, Object> s = squadra();
        s.put("roster", Collections.singletonList(null)); // roster: [null]

        rifiutata(POST, "/api/anagrafe/squadre", s, "roster[0]");
    }

    @Test
    void rosterDi13Giocatori_risponde400ConIlCampo() throws Exception {
        Map<String, Object> s = squadra();
        s.put("roster", idDiversi(13));

        rifiutata(POST, "/api/anagrafe/squadre", s, "roster");
    }

    @Test
    void rosterDi12Giocatori_siAccetta() throws Exception {
        Map<String, Object> s = squadra();
        s.put("roster", idDiversi(12));

        accettata(POST, "/api/anagrafe/squadre", s);
    }

    /* ── Password: messaggi separati per minimo e massimo; i byte li controlla UtenteService.register ── */

    @Test
    void passwordDi7Caratteri_risponde400ConIlMessaggioDelMinimo() throws Exception {
        Map<String, Object> r = registrazione("mario@x.it");
        r.put("password", "x".repeat(7));

        rifiutataConMessaggio(POST, "/api/auth/register", r,
                allOf(containsString("password:"), containsString("almeno 8 caratteri"), not(containsString("al massimo"))));
    }

    @Test
    void passwordDi73Caratteri_risponde400ConIlMessaggioDelMassimo() throws Exception {
        Map<String, Object> r = registrazione("mario@x.it");
        r.put("password", "x".repeat(73));

        rifiutataConMessaggio(POST, "/api/auth/register", r,
                allOf(containsString("password:"), containsString("al massimo 72 caratteri"), not(containsString("almeno"))));
    }

    // 40 lettere accentate sono 40 caratteri per il DTO ma 80 byte per BCrypt: senza il controllo la registrazione dava 500
    @Test
    void passwordDi40LettereAccentate_risponde400ConIlMessaggioDeiByteSenzaArrivareAlDatabase() throws Exception {
        Map<String, Object> r = registrazione("mario@x.it");
        r.put("password", "è".repeat(40));

        rifiutataConMessaggio(POST, "/api/auth/register", r, containsString("72 byte"));
    }

    @Test
    void passwordDi72ByteEsatti_siAccetta() throws Exception {
        Map<String, Object> r = registrazione("mario@x.it");
        r.put("password", "è".repeat(36)); // 36 lettere accentate = 72 byte

        accettata(POST, "/api/auth/register", r);
    }

    /* ── Corpo oltre 2 MB: 413 dal filtro, prima di Spring MVC e del database ── */

    // L'endpoint pubblico del login, senza token: è quello che chiunque può raggiungere. Il JSON è valido: senza il filtro
    // attraverserebbe la validazione e arriverebbe al servizio
    @Test
    void corpoDi3MbSuUnEndpointPubblico_risponde413ConIlCorpoStandard() throws Exception {
        Map<String, Object> accessoConPasswordEnorme = accesso("mario@x.it");
        accessoConPasswordEnorme.put("password", "x".repeat(TRE_MB));

        corpoStandard(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(accessoConPasswordEnorme)))
                .andExpect(status().isContentTooLarge()))
                .andExpect(jsonPath("$.message", containsString("2 MB")));

        verifyNoInteractions(authenticationManager, refreshTokenService, utenteRepository);
    }

    @Test
    void importDi3MbDaUtenteAutenticato_risponde413SenzaChiamareIlServizio() throws Exception {
        Map<String, Object> tappaEnorme = tappa();
        tappaEnorme.put("partite", List.of("x".repeat(TRE_MB))); // per il DTO è un array come un altro
        Map<String, Object> lega = nuovaLega();
        lega.put("tappe", List.of(tappaEnorme));

        corpoStandard(invia(POST, "/api/leghe", lega).andExpect(status().isContentTooLarge()))
                .andExpect(jsonPath("$.message", containsString("2 MB")));

        verifyNoInteractions(legaService);
    }

    // Il filtro gira dopo la catena di Spring Security: senza token su un endpoint protetto vince il 401, il corpo non si legge
    @Test
    void corpoDi3MbSenzaTokenSuEndpointProtetto_risponde401() throws Exception {
        mvc.perform(post("/api/leghe").contentType(MediaType.APPLICATION_JSON).content(new byte[TRE_MB]))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(legaService);
    }

    // ...e dopo il CORS: da un'origine ammessa il 413 porta Access-Control-Allow-Origin, altrimenti il browser non lo leggerebbe
    @Test
    void corpoDi3MbDaUnaOrigineAmmessa_il413PortaGliHeaderCors() throws Exception {
        mvc.perform(post("/api/auth/login").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON).content(new byte[TRE_MB]))
                .andExpect(status().isContentTooLarge())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }
}
