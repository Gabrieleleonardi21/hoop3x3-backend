package com.hoop3x3.backend.web;

import com.hoop3x3.backend.controllers.AnagrafeController;
import com.hoop3x3.backend.controllers.ArchivioController;
import com.hoop3x3.backend.controllers.AuthController;
import com.hoop3x3.backend.controllers.LegaController;
import com.hoop3x3.backend.controllers.TappaController;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.security.JWTtools;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.LegaService;
import com.hoop3x3.backend.services.RefreshTokenService;
import com.hoop3x3.backend.services.UtenteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validazione d'ingresso a livello web: ciò che non entra nelle colonne del database o non ha la forma attesa
 * risponde 400 con il nome del campo. I servizi sono simulati e ogni caso rifiutato verifica che nessuno sia stato
 * chiamato: sono l'unica strada verso il database, quindi una richiesta rifiutata qui non lo raggiunge.
 */
@WebMvcTest(controllers = {LegaController.class, TappaController.class, AnagrafeController.class,
        ArchivioController.class, AuthController.class})
@Import({SecurityConfig.class, JwtFilter.class, JsonAuthEntryPoint.class, AuthCookies.class, ExceptionsHandler.class,
        UtenteService.class})
class ValidazioneWebTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean LegaService legaService;
    @MockitoBean AnagrafeService anagrafeService;
    @MockitoBean ArchivioService archivioService;
    @MockitoBean RefreshTokenService refreshTokenService;
    @MockitoBean AuthenticationManager authenticationManager;
    @MockitoBean JWTtools jwtTools;
    @MockitoBean UtenteRepository utenteRepository;

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

    private static Map<String, Object> pubblicazione() {
        return new LinkedHashMap<>(Map.of("tappa", tappa(), "lega", "Circuito 2026"));
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
        invia(metodo, url, corpo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString(campo + ":")))
                .andExpect(jsonPath("$.timestamp").exists());
        verifyNoInteractions(legaService, anagrafeService, archivioService, authenticationManager, refreshTokenService, utenteRepository);
    }

    /** La validazione lascia passare la richiesta: arriva al controller e risponde 2xx */
    private void accettata(HttpMethod metodo, String url, Object corpo) throws Exception {
        invia(metodo, url, corpo).andExpect(status().is2xxSuccessful());
    }

    /* ── @Size uguali alle colonne ── */

    @Test
    void nomeDellaTappaDi121Caratteri_risponde400ConIlCampo() throws Exception {
        Map<String, Object> t = tappa();
        t.put("nome", "x".repeat(121));

        rifiutata(POST, urlNuovaTappa(), t, "nome");
    }

    @Test
    void luogoDellaTappaDi161Caratteri_risponde400ConIlCampo() throws Exception {
        Map<String, Object> t = tappa();
        t.put("luogo", "x".repeat(161));

        rifiutata(POST, urlNuovaTappa(), t, "luogo");
    }

    // La colonna è da 10 caratteri e contiene una data ISO o niente (è il valore dell'input date del frontend)
    @ParameterizedTest
    @ValueSource(strings = {"2026-6-1", "01/06/2026", "2026-06-011", "domani", " "})
    void dataDellaTappaNonIso_risponde400ConIlCampo(String data) throws Exception {
        Map<String, Object> t = tappa();
        t.put("data", data);

        rifiutata(POST, urlNuovaTappa(), t, "data");
    }

    @Test
    void tappaConValoriAlLimiteDelloSchema_siAccetta() throws Exception {
        Map<String, Object> t = tappa();
        t.put("nome", "x".repeat(120));
        t.put("luogo", "x".repeat(160));
        accettata(POST, urlNuovaTappa(), t);

        t.put("data", ""); // data vuota: la tappa non ha ancora una data
        accettata(PUT, "/api/tappe/" + UUID.randomUUID(), t);

        t.remove("data"); // data assente
        accettata(PUT, "/api/tappe/" + UUID.randomUUID(), t);
    }

    @Test
    void legaDiPubblicazioneDi121Caratteri_risponde400ConIlCampo() throws Exception {
        Map<String, Object> p = pubblicazione();
        p.put("lega", "x".repeat(121));

        rifiutata(PUT, "/api/archivio", p, "lega");
    }

    @Test
    void notaDelGiocatoreDi2001Caratteri_risponde400ConIlCampo() throws Exception {
        Map<String, Object> g = giocatore();
        g.put("note", "x".repeat(2001));

        rifiutata(POST, "/api/anagrafe/giocatori", g, "note");
    }

    @Test
    void notaDellaSquadraDi2001Caratteri_risponde400ConIlCampo() throws Exception {
        Map<String, Object> s = squadra();
        s.put("note", "x".repeat(2001));

        rifiutata(POST, "/api/anagrafe/squadre", s, "note");
    }

    @Test
    void emailDi256CaratteriInRegistrazione_risponde400ConIlCampo() throws Exception {
        rifiutata(POST, "/api/auth/register", registrazione(emailDi(256)), "email");
    }

    @Test
    void emailDi256CaratteriNelLogin_risponde400ConIlCampo() throws Exception {
        rifiutata(POST, "/api/auth/login", accesso(emailDi(256)), "email");
    }

    @Test
    void notePubblicazioneEmailAlLimiteDelloSchema_siAccettano() throws Exception {
        Map<String, Object> g = giocatore();
        g.put("note", "x".repeat(2000));
        accettata(POST, "/api/anagrafe/giocatori", g);

        Map<String, Object> s = squadra();
        s.put("note", "x".repeat(2000));
        accettata(POST, "/api/anagrafe/squadre", s);

        Map<String, Object> p = pubblicazione();
        p.put("lega", "x".repeat(120));
        accettata(PUT, "/api/archivio", p);

        // Con 255 caratteri l'email è ancora valida: il 400 dei 256 dipende solo dalla lunghezza
        accettata(POST, "/api/auth/login", accesso(emailDi(255)));
        accettata(POST, "/api/auth/register", registrazione(emailDi(255)));
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
}
