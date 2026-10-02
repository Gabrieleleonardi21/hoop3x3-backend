package com.hoop3x3.backend.security;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Il tetto dei 2 MB sul server vero (Tomcat su una porta libera, solo su 127.0.0.1), con un socket che manda le sole
 * intestazioni di una richiesta con un corpo da 50 MB dichiarato e mai inviato. Chi risponde senza leggere il corpo
 * ci mette millisecondi; chi lo legge resta in attesa. MockMvc qui non vede nulla: consegna sempre il corpo intero.
 * <p>
 * Il FormContentFilter di Spring Boot gira prima della sicurezza e di LimiteDimensioneFilter e, per PUT, PATCH e DELETE
 * con application/x-www-form-urlencoded, leggeva per intero il corpo in memoria, su qualsiasi percorso e anche senza
 * token: con un corpo vero da centinaia di megabyte il server finiva la memoria. L'API accetta solo JSON, quindi il
 * filtro è spento in application.properties (spring.mvc.formcontent.filter.enabled=false).
 */
@TestDiIntegrazione(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = "server.address=127.0.0.1") // il server di prova non è raggiungibile da altri computer
class LimiteDimensioneIT {

    /** Lunghezza dichiarata nelle intestazioni: 50 MB, 25 volte il tetto. Il corpo non parte mai */
    private static final long CORPO_DICHIARATO = 50L * 1024 * 1024;
    /** Una risposta senza lettura del corpo arriva in millisecondi: oltre questa attesa il server sta leggendo */
    private static final int ATTESA_MS = 5_000;

    @LocalServerPort int porta;
    @Autowired UtenteRepository utenti;
    @Autowired JWTtools jwt;

    // Senza token vince il 401: il corpo, che non arriva, non si aspetta. Il percorso non conta (il filtro di Spring
    // girava prima dell'instradamento), quindi è uno qualsiasi
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void corpoFormEnormeSenzaToken_risponde401SenzaLeggereIlCorpo(String metodo) throws IOException {
        assertThat(rispostaAlleSoleIntestazioni(metodo, null)).startsWith("HTTP/1.1 401");
    }

    // Con un token valido la richiesta supera la sicurezza e il filtro dei 2 MB la rifiuta con 413 dalla sola intestazione
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void corpoFormEnormeConToken_risponde413SenzaLeggereIlCorpo(String metodo) throws IOException {
        Utente utente = utenti.save(new Utente("form@test.it", "hash", "Form", Ruolo.USER));

        assertThat(rispostaAlleSoleIntestazioni(metodo, jwt.generateToken(utente))).startsWith("HTTP/1.1 413");
    }

    /** Manda le sole intestazioni e restituisce la riga di stato della risposta; senza risposta entro ATTESA_MS il test cade */
    private String rispostaAlleSoleIntestazioni(String metodo, String bearer) throws IOException {
        StringBuilder richiesta = new StringBuilder(metodo).append(" /api/qualsiasi HTTP/1.1\r\n")
                .append("Host: 127.0.0.1\r\n")
                .append("Content-Type: application/x-www-form-urlencoded\r\n")
                .append("Content-Length: ").append(CORPO_DICHIARATO).append("\r\n")
                .append("Connection: close\r\n");
        if (bearer != null) richiesta.append("Authorization: Bearer ").append(bearer).append("\r\n");
        richiesta.append("\r\n");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", porta), ATTESA_MS);
            socket.setSoTimeout(ATTESA_MS);
            socket.getOutputStream().write(richiesta.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        } catch (SocketTimeoutException e) {
            throw new AssertionError("Nessuna risposta in " + ATTESA_MS + " ms a " + metodo
                    + " con un corpo form da 50 MB: il server sta leggendo il corpo prima di rispondere", e);
        }
    }
}
