package com.hoop3x3.backend.security;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.OrologioDiProva;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * L'indirizzo del limite dietro un reverse proxy, come lo spiega il README: chi pubblica il server dietro un proxy imposta
 * server.forward-headers-strategy=native, e Tomcat sostituisce l'indirizzo del proxy con quello di X-Forwarded-For, ma solo
 * se la richiesta arriva da un proxy di cui si fida (server.tomcat.remoteip.internal-proxies: di base gli indirizzi
 * privati e locali, quindi anche 127.0.0.1 da cui parte questo client). Il limite continua a leggere
 * {@code request.getRemoteAddr()}: non c'è nessuna lettura a mano dell'intestazione.
 */
@TestDiIntegrazione(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = {"server.address=127.0.0.1", // il server di prova non è raggiungibile da altri computer
        "server.forward-headers-strategy=native", "limite.auth-al-minuto=3"})
@Import(OrologioDiProva.Configurazione.class)
class LimiteRichiesteProxyIT {

    private static final String LOGIN = "{\"email\":\"mario@test.it\",\"password\":\"password123\"}";

    @LocalServerPort int porta;
    @Autowired UtenteRepository utenti;
    @Autowired OrologioDiProva orologio;

    private ClientHttp client;
    private LogCatturato log;

    @AfterEach
    void rilasciaIlLog() {
        log.close();
    }

    @BeforeEach
    void giornoNuovoEUtente() {
        orologio.giornoNuovo();
        client = new ClientHttp(porta);
        log = new LogCatturato(LimiteRichiesteFilter.class);
        // Il costo del BCrypt sta dentro l'hash e il login lo rispetta: con 4 al posto di 12 gli accessi del test sono rapidi
        utenti.save(new Utente("mario@test.it", new BCryptPasswordEncoder(4).encode("password123"), "Mario", Ruolo.USER));
    }

    private HttpResponse<String> loginDi(String xForwardedFor) throws Exception {
        return client.post("/api/auth/login", LOGIN, "X-Forwarded-For", xForwardedFor);
    }

    // Ogni cliente dietro il proxy ha il suo contatore: senza questo (e senza la strategia) tutto il sito condividerebbe quello
    // del proxy e il limite di 10 accessi al minuto bloccherebbe gli utenti veri
    @Test
    void dietroUnProxyFidato_ognunoHaIlContatoreDelSuoIndirizzo() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(loginDi("198.51.100.1").statusCode()).isEqualTo(200);
        }
        assertThat(loginDi("198.51.100.1").statusCode()).as("il quarto dello stesso cliente").isEqualTo(429);

        assertThat(loginDi("198.51.100.2").statusCode()).as("un altro cliente dietro lo stesso proxy").isEqualTo(200);
        // Nel log c'è l'indirizzo del cliente e non quello del proxy (127.0.0.1)
        assertThat(log.righe()).containsExactly(
                "Limite di richieste superato: Troppi tentativi di accesso (massimo 3 al minuto), indirizzo 198.51.100.1");
    }

    // Il proxy accoda l'indirizzo che ha visto alla fine di X-Forwarded-For: Tomcat parte da destra e prende il primo che
    // non è un proxy fidato, quindi quello che il cliente ha scritto prima non conta
    @Test
    void ilClienteNonScegliIlProprioIndirizzo() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(loginDi("192.0.2." + i + ", 198.51.100.7").statusCode()).isEqualTo(200);
        }

        assertThat(loginDi("192.0.2.99, 198.51.100.7").statusCode())
                .as("l'indirizzo vero è l'ultimo, quello che ha aggiunto il proxy").isEqualTo(429);
        assertThat(log.righe()).containsExactly(
                "Limite di richieste superato: Troppi tentativi di accesso (massimo 3 al minuto), indirizzo 198.51.100.7");
    }
}
