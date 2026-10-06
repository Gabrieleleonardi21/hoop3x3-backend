package com.hoop3x3.backend.security;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.OrologioDiProva;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.RefreshTokenRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.CoachAiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static com.hoop3x3.backend.security.ClientHttp.assert429;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * I limiti di frequenza sul server vero: Tomcat su una porta libera (solo su 127.0.0.1), il database di prova e tutta la
 * catena di filtri, con valori bassi al posto di quelli alti del profilo di test e un orologio che si sposta a comando. Dove
 * i test web simulano la richiesta, qui il percorso e l'indirizzo li decide Tomcat: «/api/auth/%6Cogin» è davvero il login,
 * e un X-Forwarded-For scritto dal client non cambia l'indirizzo. Il Coach è simulato: Groq non si chiama mai.
 */
@TestDiIntegrazione(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = {"server.address=127.0.0.1", // il server di prova non è raggiungibile da altri computer
        "limite.auth-al-minuto=3", "limite.coach-al-minuto=2", "limite.coach-al-giorno=4"})
class LimiteRichiesteIT {

    private static final Instant INIZIO = Instant.parse("2026-10-06T10:00:20Z");
    private static final String EMAIL = "mario@test.it";
    private static final String PASSWORD = "password123";
    private static final String LOGIN = "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}";
    private static final String MESSAGGIO_ACCESSO = "Troppi tentativi di accesso: riprova tra 40 secondi";
    // Il costo del BCrypt sta dentro l'hash e il login lo rispetta: con 4 al posto di 12 gli accessi del test sono rapidi
    private static final BCryptPasswordEncoder ENCODER_VELOCE = new BCryptPasswordEncoder(4);

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

    @LocalServerPort int porta;
    @Autowired UtenteRepository utenti;
    @Autowired RefreshTokenRepository tokens;
    @Autowired JWTtools jwt;
    @Autowired OrologioDiProva orologio;
    @MockitoBean CoachAiService coach;

    private ClientHttp client;
    private LogCatturato log;

    @BeforeEach
    void giornoNuovoEClientEILogCatturato() {
        orologio.imposta(INIZIO.plus(Duration.ofDays(++giorniUsati)));
        client = new ClientHttp(porta);
        log = new LogCatturato(LimiteRichiesteFilter.class);
    }

    @AfterEach
    void rilasciaIlLog() {
        log.close();
    }

    private Utente salvaUtente() {
        return utenti.save(new Utente(EMAIL, ENCODER_VELOCE.encode(PASSWORD), "Mario", Ruolo.USER));
    }

    private HttpResponse<String> login() throws Exception {
        return client.post("/api/auth/login", LOGIN);
    }

    // L'undicesimo login del piano, qui il quarto: respinto prima del BCrypt e del servizio. Lo prova il database: il login
    // che riesce emette un refresh token, quello respinto no
    @Test
    void ilQuartoLoginDelMinuto_risponde429SenzaEmettereUnTokenDiRefresh() throws Exception {
        salvaUtente();
        for (int i = 0; i < 3; i++) {
            assertThat(login().statusCode()).isEqualTo(200);
        }
        assertThat(tokens.count()).isEqualTo(3);

        assert429(login(), 40, MESSAGGIO_ACCESSO);

        assertThat(tokens.count()).as("il login respinto non è arrivato al servizio").isEqualTo(3);
        assertThat(log.righe()).containsExactly(
                "Limite di richieste superato: Troppi tentativi di accesso (massimo 3 al minuto), indirizzo 127.0.0.1");

        orologio.avanza(Duration.ofSeconds(40)); // il minuto dopo
        assertThat(login().statusCode()).isEqualTo(200);
    }

    // Tomcat e Spring MVC decodificano il percorso prima di scegliere il controller: «/api/auth/%6Cogin» è il login, e
    // senza un conteggio sul percorso decodificato il limite si aggirerebbe cambiando una lettera
    @Test
    void unPercorsoConUnaLetteraCodificata_contaComeIlLoginSulServerVero() throws Exception {
        salvaUtente();

        assertThat(client.post("/api/auth/%6Cogin", LOGIN).statusCode()).as("è il login: 200").isEqualTo(200);
        assertThat(login().statusCode()).isEqualTo(200);
        assertThat(client.post("/api/auth/log%69n", LOGIN).statusCode()).isEqualTo(200);

        assertThat(login().statusCode()).isEqualTo(429);
    }

    // L'indirizzo è quello della connessione: senza server.forward-headers-strategy un X-Forwarded-For scritto dal client non
    // conta, e un valore diverso a ogni richiesta non basta per non incontrare mai il limite (vedi LimiteRichiesteProxyIT)
    @Test
    void unXForwardedForSpecificatoDalClient_nonCambiaIlContatore() throws Exception {
        salvaUtente();
        for (int i = 0; i < 3; i++) {
            assertThat(client.post("/api/auth/login", LOGIN, "X-Forwarded-For", "198.51.100." + i).statusCode()).isEqualTo(200);
        }

        assertThat(client.post("/api/auth/login", LOGIN, "X-Forwarded-For", "198.51.100.200").statusCode()).isEqualTo(429);
    }

    @Test
    void laTerzaRichiestaAlCoachNelMinuto_risponde429SenzaChiamareGroq() throws Exception {
        String bearer = "Bearer " + jwt.generateToken(salvaUtente());
        String chat = "{\"messages\":[{\"role\":\"user\",\"content\":\"ciao\"}]}";
        for (int i = 0; i < 2; i++) {
            assertThat(client.post("/api/coach/chat", chat, "Authorization", bearer).statusCode()).isEqualTo(200);
        }

        assert429(client.post("/api/coach/chat", chat, "Authorization", bearer), 40,
                "Troppe richieste al Coach AI: riprova tra 40 secondi");

        verify(coach, times(2)).chat(any());
    }
}
