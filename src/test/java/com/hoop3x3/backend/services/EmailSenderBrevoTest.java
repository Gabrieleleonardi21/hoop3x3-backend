package com.hoop3x3.backend.services;

import ch.qos.logback.classic.Level;
import com.hoop3x3.backend.LogCatturato;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'invio vero, verso un Brevo finto (BrevoFinto) con il vero client HTTP: che cosa parte (corpo, intestazioni, mittente,
 * destinatario), quando non parte niente (chiave o mittente mancanti), che l'invio avviene fuori dal thread della richiesta, e
 * che un errore di Brevo (401, 400), un timeout o la rete finiscono nel log con lo stato e il tipo dell'errore, senza il corpo
 * della risposta, la chiave o l'indirizzo intero.
 */
class EmailSenderBrevoTest {

    private static final String CHIAVE = "xkeysib-chiave-di-prova";
    private static final String MITTENTE = "hoop3x3@gmail.com";
    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private BrevoFinto brevo;
    // Esegue subito: questi test guardano che cosa arriva a Brevo. L'invio su un altro thread lo prova il test dedicato
    private final Executor subito = Runnable::run;
    private LogCatturato log;

    @BeforeEach
    void avviaBrevoFinto() throws IOException {
        brevo = new BrevoFinto();
        log = new LogCatturato(EmailSenderBrevo.class);
    }

    @AfterEach
    void fermaBrevoFinto() {
        brevo.close();
        log.close();
    }

    private EmailSenderBrevo sender(String chiave, String mittente, Executor esecutore) {
        return new EmailSenderBrevo(brevo.url(), chiave, mittente, TIMEOUT, esecutore);
    }

    @Test
    void invioRiuscito_mandaAllApiIlCorpoELeIntestazioniDiBrevo() {
        sender(CHIAVE, MITTENTE, subito).invia("mario@x.it", "Reimposta la password di HOOP 3X3",
                "Apri https://hoop3x3.onrender.com/reimposta-password?token=abc entro 30 minuti",
                "<p><a href=\"https://hoop3x3.onrender.com/reimposta-password?token=abc\">Reimposta la password</a></p>");

        assertThat(brevo.richieste()).singleElement().satisfies(richiesta -> {
            assertThat(richiesta.metodo()).isEqualTo("POST");
            assertThat(richiesta.apiKey()).isEqualTo(CHIAVE);
            assertThat(richiesta.accept()).isEqualTo("application/json");
            assertThat(richiesta.contentType()).startsWith("application/json");
            JsonNode corpo = JsonMapper.builder().build().readTree(richiesta.corpo());
            assertThat(corpo.path("sender").path("email").asString()).isEqualTo(MITTENTE);
            assertThat(corpo.path("sender").path("name").asString()).isEqualTo("HOOP 3X3");
            assertThat(corpo.path("to")).hasSize(1);
            assertThat(corpo.path("to").get(0).path("email").asString()).isEqualTo("mario@x.it");
            assertThat(corpo.path("subject").asString()).isEqualTo("Reimposta la password di HOOP 3X3");
            // L'HTML per i client che lo mostrano, il testo semplice come riserva per gli altri: partono tutti e due
            assertThat(corpo.path("textContent").asString())
                    .isEqualTo("Apri https://hoop3x3.onrender.com/reimposta-password?token=abc entro 30 minuti");
            assertThat(corpo.path("htmlContent").asString())
                    .isEqualTo("<p><a href=\"https://hoop3x3.onrender.com/reimposta-password?token=abc\">Reimposta la password</a></p>");
        });
        assertThat(log.righe()).isEmpty();
    }

    // La richiesta HTTP del recupero non aspetta Brevo: l'invio è un compito dell'esecutore
    @Test
    void lInvioAvvieneFuoriDalThreadDellaRichiesta() {
        List<Runnable> inAttesa = new ArrayList<>();
        sender(CHIAVE, MITTENTE, inAttesa::add).invia("mario@x.it", "Oggetto", "Testo", "<p>Testo</p>");

        assertThat(brevo.richieste()).isEmpty();
        inAttesa.forEach(Runnable::run);
        assertThat(brevo.richieste()).hasSize(1);
    }

    // 401: la chiave è sbagliata o revocata. 400: per esempio il mittente non è verificato in Brevo. Nel log lo stato e il tipo
    // dell'errore, mai il corpo della risposta (può contenere dati dell'account) né la chiave
    @ParameterizedTest
    @CsvSource(value = {
            "401;Unauthorized;{\"code\":\"unauthorized\",\"message\":\"Key not found\"}",
            "400;BadRequest;{\"code\":\"invalid_parameter\",\"message\":\"sender hoop3x3@gmail.com is not valid\"}",
            "500;InternalServerError;{\"code\":\"internal_error\",\"message\":\"errore interno\"}"}, delimiter = ';')
    void unErroreDiBrevo_finisceNelLogConLoStatoSenzaCorpoNeChiave(int stato, String tipo, String risposta) {
        brevo.risponde(stato, risposta);

        sender(CHIAVE, MITTENTE, subito).invia("mario@x.it", "Oggetto", "Testo", "<p>Testo</p>");

        assertThat(log.livelli()).containsExactly(Level.ERROR);
        assertThat(log.righe().getFirst())
                .contains("ma***@x.it", String.valueOf(stato), "(" + tipo + ")")
                .doesNotContain("mario@x.it", CHIAVE, "message", "not valid", "Key not found", MITTENTE);
    }

    @Test
    void unTimeoutDiBrevo_finisceNelLogConIlTipoDellErrore() {
        brevo.rispondeDopo(TIMEOUT.toMillis() * 4);

        sender(CHIAVE, MITTENTE, subito).invia("mario@x.it", "Oggetto", "Testo", "<p>Testo</p>");

        assertThat(log.livelli()).containsExactly(Level.ERROR);
        assertThat(log.righe().getFirst()).contains("ma***@x.it", "Exception").doesNotContain("mario@x.it", CHIAVE, "127.0.0.1");
    }

    @Test
    void brevoIrraggiungibile_finisceNelLogConIlTipoDellErrore() {
        EmailSenderBrevo sender = sender(CHIAVE, MITTENTE, subito);
        brevo.close();

        sender.invia("mario@x.it", "Oggetto", "Testo", "<p>Testo</p>");

        assertThat(log.livelli()).containsExactly(Level.ERROR);
        assertThat(log.righe().getFirst()).contains("ma***@x.it", "Exception").doesNotContain("mario@x.it", CHIAVE, "127.0.0.1");
    }

    // Senza chiave (o senza mittente) nessuna chiamata: un avviso all'avvio dice perché, e l'endpoint risponde lo stesso
    @ParameterizedTest
    @CsvSource(value = {
            "'';hoop3x3@gmail.com;Recupero password: BREVO_API_KEY mancante, le email non partono",
            "'   ';hoop3x3@gmail.com;Recupero password: BREVO_API_KEY mancante, le email non partono",
            "xkeysib-chiave-di-prova;'';Recupero password: MAIL_USERNAME mancante (il mittente), le email non partono"},
            delimiter = ';')
    void senzaChiaveOMittente_unAvvisoAllAvvio_eNessunaChiamata(String chiave, String mittente, String avviso) {
        EmailSenderBrevo sender = sender(chiave, mittente, subito);

        assertThat(log.righe()).containsExactly(avviso);
        assertThat(log.livelli()).containsExactly(Level.WARN);

        sender.invia("mario@x.it", "Oggetto", "Testo", "<p>Testo</p>");

        assertThat(brevo.richieste()).isEmpty();
    }

    @Test
    void chiaveEMittenteNonImpostati_valgonoComeVuoti() {
        sender(null, null, subito).invia("mario@x.it", "Oggetto", "Testo", "<p>Testo</p>");

        assertThat(brevo.richieste()).isEmpty();
    }
}
