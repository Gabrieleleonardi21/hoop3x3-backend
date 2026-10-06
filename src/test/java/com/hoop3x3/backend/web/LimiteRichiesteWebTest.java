package com.hoop3x3.backend.web;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.OrologioDiProva;
import com.hoop3x3.backend.controllers.AuthController;
import com.hoop3x3.backend.controllers.CoachController;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JWTtools;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.LimiteDimensioneFilter;
import com.hoop3x3.backend.security.LimiteRichiesteFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.CoachAiService;
import com.hoop3x3.backend.services.RefreshTokenService;
import com.hoop3x3.backend.services.UtenteService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * I limiti di frequenza dentro la catena vera: filtro di sicurezza, JwtFilter, controller e ExceptionsHandler veri, servizi
 * simulati, i valori di produzione (10 accessi al minuto per indirizzo, per il Coach 20 al minuto e 300 al giorno per
 * utente) e un orologio che si sposta a comando. Una richiesta respinta non deve arrivare al servizio: niente BCrypt,
 * niente chiamata a Groq.
 */
@WebMvcTest(controllers = {AuthController.class, CoachController.class})
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JWTtools.class, JsonAuthEntryPoint.class,
        AuthCookies.class, ExceptionsHandler.class})
@TestPropertySource(properties = {"jwt.secret=0123456789abcdef0123456789abcdef", "cors.origins=http://localhost:5173"})
class LimiteRichiesteWebTest {

    /** Il secondo 20 di un minuto, a metà giornata: al minuto dopo mancano 40 secondi, a mezzanotte UTC 13 ore, 59 minuti e 40 */
    private static final Instant INIZIO = Instant.parse("2026-10-06T10:00:20Z");
    private static final String INDIRIZZO = "203.0.113.9";
    private static final String CORPO_LOGIN = "{\"email\":\"mario@x.it\",\"password\":\"password-valida\"}";
    private static final String MESSAGGIO_ACCESSO = "Troppi tentativi di accesso: riprova tra 40 secondi";
    private static final String MESSAGGIO_COACH = "Troppe richieste al Coach AI: riprova tra 40 secondi";

    /** Ogni test parte da un giorno diverso: i contatori vivono nel contesto di Spring, che i test della classe condividono */
    private static int giorniUsati;

    /** È questo l'orologio dei limiti: sostituisce quello vero, perché non sia il minuto vero a dire quando si riparte */
    @TestConfiguration
    static class OrologioFinto {
        @Bean
        @Primary
        OrologioDiProva orologioDiProva() {
            return new OrologioDiProva(INIZIO.toString());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JWTtools jwt;
    @Autowired OrologioDiProva orologio;
    @MockitoBean AuthenticationManager authenticationManager;
    @MockitoBean UtenteService utenteService;
    @MockitoBean RefreshTokenService refreshTokenService;
    @MockitoBean CoachAiService coachAiService;
    @MockitoBean UtenteRepository utenteRepository;

    private final Utente mario = utente("mario@x.it");
    private final Utente luca = utente("luca@x.it");

    private LogCatturato log;

    @BeforeEach
    void catturaIlLog() {
        log = new LogCatturato(LimiteRichiesteFilter.class);
    }

    @AfterEach
    void rilasciaIlLog() {
        log.close();
    }

    @BeforeEach
    void giornoNuovoEServiziCheRispondonoBene() {
        orologio.imposta(INIZIO.plus(Duration.ofDays(++giorniUsati)));
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(mario, null, mario.getAuthorities()));
        when(utenteService.register(any())).thenReturn(mario);
        when(refreshTokenService.emetti(any())).thenReturn("refresh");
        when(refreshTokenService.ruota("vecchio")).thenReturn(new RefreshTokenService.Rinnovo(mario, "nuovo"));
        when(coachAiService.chat(any())).thenReturn(mapper.createObjectNode());
        // Il JwtFilter riconosce gli utenti dei token dal database: qui sono questi due
        when(utenteRepository.findById(mario.getId())).thenReturn(Optional.of(mario));
        when(utenteRepository.findById(luca.getId())).thenReturn(Optional.of(luca));
    }

    /* ── Aiuti ── */

    private static Utente utente(String email) {
        Utente utente = new Utente(email, "hash", "Nome", Ruolo.USER);
        ReflectionTestUtils.setField(utente, "id", UUID.randomUUID()); // il database lo assegnerebbe al salvataggio
        return utente;
    }

    /** MockMvc fa partire ogni richiesta da 127.0.0.1: questo la fa arrivare dall'indirizzo dato */
    private static RequestPostProcessor da(String indirizzo) {
        return richiesta -> {
            richiesta.setRemoteAddr(indirizzo);
            return richiesta;
        };
    }

    private ResultActions login(String indirizzo) throws Exception {
        return loginCon(post("/api/auth/login"), indirizzo);
    }

    /** Un login fatto con la richiesta data (percorso e intestazioni a scelta) dall'indirizzo dato */
    private ResultActions loginCon(MockHttpServletRequestBuilder richiesta, String indirizzo) throws Exception {
        return mvc.perform(richiesta.with(da(indirizzo)).contentType(MediaType.APPLICATION_JSON).content(CORPO_LOGIN));
    }

    private ResultActions registrazione(String indirizzo) throws Exception {
        return mvc.perform(post("/api/auth/register").with(da(indirizzo)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Mario\",\"email\":\"mario@x.it\",\"password\":\"password-valida\"}"));
    }

    private ResultActions rinnovo(String indirizzo) throws Exception {
        return mvc.perform(post("/api/auth/refresh").with(da(indirizzo)).cookie(new Cookie("hoop3x3_refresh", "vecchio")));
    }

    private ResultActions chat(Utente chi) throws Exception {
        return mvc.perform(post("/api/coach/chat").header("Authorization", "Bearer " + jwt.generateToken(chi))
                .contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[{\"role\":\"user\",\"content\":\"ciao\"}]}"));
    }

    private void ilLoginPassa(int quante, String indirizzo) throws Exception {
        for (int i = 0; i < quante; i++) {
            login(indirizzo).andExpect(status().isOk());
        }
    }

    private void laChatPassa(int quante, Utente chi) throws Exception {
        for (int i = 0; i < quante; i++) {
            chat(chi).andExpect(status().isOk());
        }
    }

    /** 429 con il corpo di tutti gli errori ({message, timestamp}, niente altro), il Retry-After in secondi e il messaggio */
    private static void assert429(ResultActions risposta, long secondi, String messaggio) throws Exception {
        risposta.andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, String.valueOf(secondi)))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.message").value(messaggio))
                .andExpect(jsonPath("$.timestamp", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?")));
    }

    /* ── Login, registrazione e rinnovo del token ── */

    // La verifica del piano: l'undicesimo login in un minuto dà 429, e l'autenticazione (cioè il BCrypt) non parte
    @Test
    void undicesimoLoginDelMinuto_risponde429SenzaArrivareAllAutenticazione() throws Exception {
        ilLoginPassa(10, INDIRIZZO);

        assert429(login(INDIRIZZO), 40, MESSAGGIO_ACCESSO);

        verify(authenticationManager, times(10)).authenticate(any());
    }

    // Chi insiste nello stesso minuto riceve sempre il 429, ma nei log resta una riga sola: l'insistenza non riempie i log
    @Test
    void chiInsiste_riceveSempreIl429EILogHaUnaRigaSola() throws Exception {
        ilLoginPassa(10, INDIRIZZO);

        for (int i = 0; i < 5; i++) {
            assert429(login(INDIRIZZO), 40, MESSAGGIO_ACCESSO);
        }

        assertThat(log.righe()).containsExactly(
                "Limite di richieste superato: Troppi tentativi di accesso (massimo 10 al minuto), indirizzo " + INDIRIZZO);
    }

    // ...e passato il minuto è di nuovo consentito
    @Test
    void passatoIlMinuto_ilLoginTornaConsentito() throws Exception {
        ilLoginPassa(10, INDIRIZZO);
        assert429(login(INDIRIZZO), 40, MESSAGGIO_ACCESSO);

        orologio.avanza(Duration.ofSeconds(39)); // 10:00:59: l'ultimo secondo del minuto
        assert429(login(INDIRIZZO), 1, "Troppi tentativi di accesso: riprova tra 1 secondo");
        orologio.avanza(Duration.ofSeconds(1)); // 10:01:00

        ilLoginPassa(10, INDIRIZZO);
        assert429(login(INDIRIZZO), 60, "Troppi tentativi di accesso: riprova tra 1 minuto");
    }

    @Test
    void undicesimaRegistrazioneDelMinuto_risponde429SenzaCreareUnAccount() throws Exception {
        for (int i = 0; i < 10; i++) {
            registrazione(INDIRIZZO).andExpect(status().isCreated());
        }

        assert429(registrazione(INDIRIZZO), 40, "Troppe richieste di registrazione: riprova tra 40 secondi");

        verify(utenteService, times(10)).register(any());
    }

    @Test
    void undicesimoRinnovoDelMinuto_risponde429SenzaRuotareIlToken() throws Exception {
        for (int i = 0; i < 10; i++) {
            rinnovo(INDIRIZZO).andExpect(status().isOk());
        }

        assert429(rinnovo(INDIRIZZO), 40, "Troppi rinnovi della sessione: riprova tra 40 secondi");

        verify(refreshTokenService, times(10)).ruota("vecchio");
    }

    @Test
    void indirizziDiversi_hannoContatoriSeparati() throws Exception {
        ilLoginPassa(10, "203.0.113.1");
        assert429(login("203.0.113.1"), 40, MESSAGGIO_ACCESSO);

        ilLoginPassa(10, "203.0.113.2");
    }

    @Test
    void ogniEndpointHaIlSuoContatore() throws Exception {
        ilLoginPassa(10, INDIRIZZO);
        assert429(login(INDIRIZZO), 40, MESSAGGIO_ACCESSO);

        registrazione(INDIRIZZO).andExpect(status().isCreated());
        rinnovo(INDIRIZZO).andExpect(status().isOk());
    }

    // L'indirizzo è quello della connessione. Un client può scrivere X-Forwarded-For come vuole: se il limite lo leggesse,
    // basterebbe un valore diverso a ogni richiesta per non incontrarlo mai (chi pubblica dietro un proxy lo dice a Tomcat
    // con server.forward-headers-strategy: vedi il README)
    @Test
    void unXForwardedForSpecificatoDalClient_nonCambiaIlContatore() throws Exception {
        for (int i = 0; i < 10; i++) {
            loginCon(post("/api/auth/login").header("X-Forwarded-For", "198.51.100." + i), INDIRIZZO).andExpect(status().isOk());
        }

        loginCon(post("/api/auth/login").header("X-Forwarded-For", "198.51.100.200"), INDIRIZZO)
                .andExpect(status().isTooManyRequests());
    }

    // Il 429 passa dalla catena di sicurezza dopo il filtro CORS, come il 413 e il 401: da un'origine ammessa il browser lo
    // può leggere, e il frontend può mostrare il messaggio invece di un generico «errore di rete»
    @Test
    void il429PortaGliHeaderCors() throws Exception {
        ilLoginPassa(10, INDIRIZZO);

        loginCon(post("/api/auth/login").header(HttpHeaders.ORIGIN, "http://localhost:5173"), INDIRIZZO)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    // Spring MVC decodifica il percorso prima di scegliere il controller: «/api/auth/%6Cogin» è il login. Se il filtro non
    // lo contasse, il limite si aggirerebbe cambiando una lettera
    @Test
    void unPercorsoConUnaLetteraCodificata_contaComeIlLogin() throws Exception {
        for (int i = 0; i < 10; i++) {
            loginCon(post(URI.create("/api/auth/%6Cogin")), INDIRIZZO).andExpect(status().isOk());
        }

        assert429(login(INDIRIZZO), 40, MESSAGGIO_ACCESSO);
    }

    // Il limite gira prima di LimiteDimensioneFilter: anche le richieste da 413 si contano, e oltre il limite l'indirizzo riceve
    // il 429 invece di costringere il server a esaminare un altro corpo di troppo
    @Test
    void leRichiesteOltre2MbSiContano_eOltreIlLimiteRispondeIl429() throws Exception {
        byte[] enorme = new byte[LimiteDimensioneFilter.LIMITE_BYTE + 1]; // un byte oltre il tetto
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/auth/login").with(da(INDIRIZZO)).contentType(MediaType.APPLICATION_JSON).content(enorme))
                    .andExpect(status().isContentTooLarge());
        }

        assert429(mvc.perform(post("/api/auth/login").with(da(INDIRIZZO)).contentType(MediaType.APPLICATION_JSON).content(enorme)),
                40, MESSAGGIO_ACCESSO);
    }

    /* ── Coach AI ── */

    @Test
    void ventunesimaRichiestaAlCoachNelMinuto_risponde429SenzaChiamareGroq() throws Exception {
        laChatPassa(20, mario);

        assert429(chat(mario), 40, MESSAGGIO_COACH);

        verify(coachAiService, times(20)).chat(any());
    }

    @Test
    void trecentunesimaRichiestaAlCoachNelGiorno_risponde429FinoAMezzanotteUtc() throws Exception {
        for (int minuto = 0; minuto < 15; minuto++) { // 15 minuti da 20 richieste: 300, la quota del giorno
            laChatPassa(20, mario);
            orologio.avanza(Duration.ofMinutes(1));
        }
        long aMezzanotte = 24 * 3600 - (10 * 3600 + 15 * 60 + 20); // ora sono le 10:15:20

        assert429(chat(mario), aMezzanotte, "Quota giornaliera del Coach AI esaurita: riprova tra 14 ore");
        verify(coachAiService, times(300)).chat(any());

        orologio.avanza(Duration.ofSeconds(aMezzanotte)); // mezzanotte UTC: il giorno nuovo ha la sua quota
        chat(mario).andExpect(status().isOk());
    }

    @Test
    void utentiDiversi_hannoQuoteSeparate() throws Exception {
        laChatPassa(20, mario);
        assert429(chat(mario), 40, MESSAGGIO_COACH);

        laChatPassa(20, luca);
    }

    // Senza token risponde il 401 e il filtro non conta niente: altrimenti chiunque potrebbe togliere le richieste a un utente
    // vero, e le richieste anonime riempirebbero la mappa dei contatori
    @Test
    void senzaToken_ilCoachRisponde401ENessunContatoreSiMuove() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/api/coach/chat").contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[]}"))
                    .andExpect(status().isUnauthorized());
        }

        laChatPassa(20, mario);
    }

    // Lo stato del Coach lo legge il frontend a ogni avvio e non chiama Groq: non consuma la quota
    @Test
    void ilStatoDelCoachNonSiConta() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(get("/api/coach/status").header("Authorization", "Bearer " + jwt.generateToken(mario)))
                    .andExpect(status().isOk());
        }

        laChatPassa(20, mario);
    }
}
