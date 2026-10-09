package com.hoop3x3.backend.services;

import com.hoop3x3.backend.RichiesteContemporanee;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.entities.RefreshToken;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.RefreshTokenRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.support.Tempo;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Refresh token contro PostgreSQL vero. A ogni accesso il servizio cancella i token scaduti dell'utente: quando le
 * righe si caricavano e si cancellavano una a una, due accessi contemporanei dello stesso utente trovavano le stesse
 * righe e quello che arrivava dopo falliva (ObjectOptimisticLockingFailureException, 500). Con i mock non si vede:
 * servono un database vero e più accessi insieme (BE-20). Anche la gara tra due rinnovi dello stesso cookie si prova qui.
 * MockMvc va bene da più thread, quindi non serve un server su una porta (RANDOM_PORT).
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class RefreshTokenIT {

    private static final String EMAIL = "mario@test.it";
    private static final String PASSWORD = "password123";
    private static final int ACCESSI = 8;
    private static final int TOKEN_SCADUTI = 40;
    /** Tetto d'attesa: un thread rimasto indietro fa fallire il test, non lo blocca */
    private static final int TIMEOUT_SECONDI = 60;
    // Il costo del BCrypt sta dentro l'hash e il login lo rispetta. Con quello di produzione (12) ogni accesso passa
    // 200-300 ms nel BCrypt e le richieste arrivano alla pulizia sfalsate: il difetto si vedrebbe solo a volte
    private static final BCryptPasswordEncoder ENCODER_VELOCE = new BCryptPasswordEncoder(4);

    @Autowired MockMvc mvc;
    @Autowired UtenteRepository utenti;
    @Autowired RefreshTokenRepository tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transazioni;

    @Test
    void accessiContemporaneiDelloStessoUtenteConTokenScadutiRispondonoTutti200() throws Exception {
        Utente mario = salvaUtente(EMAIL);
        tokens.saveAll(tokenScaduti(mario, TOKEN_SCADUTI));

        assertThat(accessiContemporanei()).hasSize(ACCESSI).containsOnly(200);

        // La pulizia c'è stata: dei token scaduti non resta nessuno, solo i token nuovi (uno per accesso)
        assertThat(tokens.findAll()).hasSize(ACCESSI).noneMatch(RefreshToken::isScaduto);
    }

    // La query di pulizia ha due condizioni, utente e scadenza: un test sul servizio con il repository simulato non
    // le vedrebbe, e senza una delle due l'accesso di Mario cancellerebbe token che non sono suoi o ancora validi
    @Test
    void laPuliziaCancellaSoloITokenScadutiDiChiAccede() throws Exception {
        Utente mario = salvaUtente(EMAIL);
        Utente luigi = salvaUtente("luigi@test.it");
        tokens.saveAll(tokenScaduti(mario, 3));
        RefreshToken validoDiMario = tokens.save(token(mario, Tempo.adesso().plusDays(1)));
        RefreshToken scadutoDiLuigi = tokens.save(token(luigi, Tempo.adesso().minusDays(1)));

        assertThat(accesso()).isEqualTo(200);

        // Restano il token ancora valido di Mario, quello scaduto di Luigi e il token nuovo di Mario
        assertThat(tokens.findAll()).extracting(RefreshToken::getTokenHash)
                .hasSize(3)
                .contains(validoDiMario.getTokenHash(), scadutoDiLuigi.getTokenHash());
    }

    /* ── Due rinnovi con lo stesso cookie, insieme: uno vince, l'altro perde senza un 500 ── */

    // Due schede aperte rinnovano insieme con lo stesso cookie (RefreshTokenServiceTest lo prova solo con i mock). Con il
    // database vero: una riceve 200 e il cookie nuovo, l'altra è respinta, 409 se ha letto il token prima che la vincitrice
    // lo cancellasse (vedi il test sotto) o 401 se l'ha cercato dopo; nessuna delle due dà 500, e in tabella resta un solo
    // token, quello nuovo
    @Test
    void dueRinnoviContemporaneiDelloStessoCookie_unoPassaELAltroERespintoSenza500() throws Exception {
        salvaUtente(EMAIL);
        String cookie = cookieDelLogin();
        CyclicBarrier partenza = new CyclicBarrier(2);
        Callable<Integer> rinnovoAllaPartenza = () -> {
            partenza.await(TIMEOUT_SECONDI, TimeUnit.SECONDS);
            return rinnovo(cookie).getStatus();
        };

        List<Integer> stati = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (Future<Integer> esito : pool.invokeAll(List.of(rinnovoAllaPartenza, rinnovoAllaPartenza), TIMEOUT_SECONDI, TimeUnit.SECONDS)) {
                stati.add(esito.get());
            }
        }

        assertThat(stati).hasSize(2).containsOnlyOnce(200).doesNotContain(500);
        assertThat(stati).filteredOn(stato -> stato == 401 || stato == 409).as("il rinnovo che perde").hasSize(1);
        assertThat(tokens.findAll()).as("il vecchio token è cancellato e ne resta uno solo, quello nuovo")
                .hasSize(1).noneMatch(t -> t.getTokenHash().equals(RefreshTokenService.sha256(cookie)));
    }

    // L'intreccio preciso, forzato e non lasciato al caso: la prima richiesta ha cancellato il token ma non ha ancora confermato,
    // la seconda lo legge (c'è ancora), si ferma sulla riga bloccata e, al commit della prima, la sua DELETE non trova più niente:
    // 409 «già rinnovata», il messaggio che il client legge per riprovare con il cookie nuovo
    @Test
    void unRinnovoCheTrovaIlTokenCancellatoDaUnAltroInCorso_risponde409() throws Exception {
        salvaUtente(EMAIL);
        String cookie = cookieDelLogin();
        RichiesteContemporanee insieme = new RichiesteContemporanee(jdbc, transazioni);

        MockHttpServletResponse risposta = insieme.mentreUnaTransazioneTieneUnaRiga(
                () -> jdbc.update("delete from refresh_tokens where token_hash = ?", RefreshTokenService.sha256(cookie)),
                () -> rinnovo(cookie));

        assertThat(risposta.getStatus()).isEqualTo(409);
        assertThat(risposta.getContentAsString(StandardCharsets.UTF_8))
                .contains("Sessione già rinnovata da un'altra richiesta: riprova");
        assertThat(tokens.count()).as("nessun token nuovo: il rinnovo respinto non ne emette").isZero();
    }

    /** Un accesso con le credenziali di Mario: restituisce lo stato HTTP della risposta */
    private int accesso() throws Exception {
        return login().getStatus();
    }

    private MockHttpServletResponse login() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse();
    }

    /** Il refresh token in chiaro che il login ha messo nel cookie */
    private String cookieDelLogin() throws Exception {
        MockHttpServletResponse risposta = login();
        assertThat(risposta.getStatus()).isEqualTo(200);
        return risposta.getCookie(AuthCookies.NOME).getValue();
    }

    /** Un rinnovo con quel cookie: la risposta intera */
    private MockHttpServletResponse rinnovo(String cookie) throws Exception {
        return mvc.perform(post("/api/auth/refresh").cookie(new Cookie(AuthCookies.NOME, cookie))).andReturn().getResponse();
    }

    /** ACCESSI accessi di Mario su thread diversi, fatti partire insieme da una barriera: restituisce lo stato di ciascuno */
    private List<Integer> accessiContemporanei() throws Exception {
        CyclicBarrier partenza = new CyclicBarrier(ACCESSI);
        Callable<Integer> accessoAllaPartenza = () -> {
            partenza.await(TIMEOUT_SECONDI, TimeUnit.SECONDS);
            return accesso();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(ACCESSI)) {
            List<Integer> stati = new ArrayList<>();
            for (Future<Integer> esito : pool.invokeAll(Collections.nCopies(ACCESSI, accessoAllaPartenza), TIMEOUT_SECONDI, TimeUnit.SECONDS)) {
                stati.add(esito.get());
            }
            return stati;
        }
    }

    private Utente salvaUtente(String email) {
        return utenti.save(new Utente(email, ENCODER_VELOCE.encode(PASSWORD), "Utente", Ruolo.USER));
    }

    /** Token con un hash diverso da ogni altro (la colonna è unica) e la scadenza indicata */
    private static RefreshToken token(Utente utente, LocalDateTime scadenza) {
        return new RefreshToken(utente, RefreshTokenService.sha256(UUID.randomUUID().toString()), scadenza);
    }

    /** Token scaduti da ieri */
    private static List<RefreshToken> tokenScaduti(Utente utente, int quanti) {
        return IntStream.range(0, quanti).mapToObj(_ -> token(utente, Tempo.adesso().minusDays(1))).toList();
    }
}
