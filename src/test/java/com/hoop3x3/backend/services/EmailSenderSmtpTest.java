package com.hoop3x3.backend.services;

import ch.qos.logback.classic.Level;
import com.hoop3x3.backend.LogCatturato;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * L'invio vero, con JavaMailSender simulato: chi è il mittente, quando non parte niente (credenziali mancanti), che l'invio
 * avviene fuori dal thread della richiesta e che un errore di Gmail finisce nel log senza l'indirizzo intero.
 */
class EmailSenderSmtpTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    // L'esecutore finto tiene da parte i compiti: il test decide quando eseguirli, come farebbe un altro thread
    private final List<Runnable> inAttesa = new ArrayList<>();
    private final Executor esecutore = inAttesa::add;
    private final LogCatturato log = new LogCatturato(EmailSenderSmtp.class);

    @AfterEach
    void rilasciaIlLog() {
        log.close();
    }

    private static MailProperties credenziali(String username, String password) {
        MailProperties proprieta = new MailProperties();
        proprieta.setUsername(username);
        proprieta.setPassword(password);
        return proprieta;
    }

    @Test
    void conLeCredenziali_inviaUnTestoSempliceDalMittenteMailUsername_fuoriDalThreadDellaRichiesta() {
        EmailSenderSmtp sender = new EmailSenderSmtp(mailSender, credenziali("hoop3x3@gmail.com", "password-per-app"), esecutore);

        sender.invia("mario@x.it", "Oggetto", "Testo");

        // Finché l'esecutore non gira non parte niente: la richiesta HTTP non aspetta Gmail
        verifyNoInteractions(mailSender);
        inAttesa.forEach(Runnable::run);
        ArgumentCaptor<SimpleMailMessage> messaggio = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messaggio.capture());
        assertThat(messaggio.getValue().getFrom()).isEqualTo("hoop3x3@gmail.com");
        assertThat(messaggio.getValue().getTo()).containsExactly("mario@x.it");
        assertThat(messaggio.getValue().getSubject()).isEqualTo("Oggetto");
        assertThat(messaggio.getValue().getText()).isEqualTo("Testo");
    }

    @ParameterizedTest
    @CsvSource(value = {"'';password-per-app", "hoop3x3@gmail.com;''", "'';''", "'   ';password-per-app"}, delimiter = ';')
    void senzaUsernameOPassword_unAvvisoAllAvvio_eLeEmailNonPartono(String username, String password) {
        EmailSenderSmtp sender = new EmailSenderSmtp(mailSender, credenziali(username, password), esecutore);

        assertThat(log.righe()).containsExactly("Recupero password: MAIL_USERNAME o MAIL_PASSWORD mancanti, le email non partono");
        assertThat(log.livelli()).containsExactly(Level.WARN);

        sender.invia("mario@x.it", "Oggetto", "Testo");

        assertThat(inAttesa).isEmpty();
        verifyNoInteractions(mailSender);
    }

    @Test
    void credenzialiNonImpostate_valgonoComeVuote() {
        EmailSenderSmtp sender = new EmailSenderSmtp(mailSender, new MailProperties(), esecutore);

        sender.invia("mario@x.it", "Oggetto", "Testo");

        assertThat(inAttesa).isEmpty();
        verifyNoInteractions(mailSender);
    }

    // L'errore resta nel thread dell'invio (la risposta è già partita): una riga d'errore con l'indirizzo mascherato e il tipo
    // dell'errore, senza il messaggio di Gmail, che può contenere l'indirizzo intero
    @Test
    void unErroreDiGmail_finisceNelLogConLEmailMascherata() {
        EmailSenderSmtp sender = new EmailSenderSmtp(mailSender, credenziali("hoop3x3@gmail.com", "password-per-app"), esecutore);
        doThrow(new MailAuthenticationException("535 rifiutata per mario@x.it")).when(mailSender).send(any(SimpleMailMessage.class));

        sender.invia("mario@x.it", "Oggetto", "Testo");
        inAttesa.forEach(Runnable::run);

        assertThat(log.livelli()).containsExactly(Level.ERROR);
        assertThat(log.righe().getFirst()).contains("ma***@x.it", "MailAuthenticationException").doesNotContain("mario@x.it");
    }
}
