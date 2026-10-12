package com.hoop3x3.backend.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

import static com.hoop3x3.backend.services.LogSupport.mascheraEmail;

/**
 * Le email vere, da Gmail SMTP (spring.mail.* in application.properties) con mittente MAIL_USERNAME.
 * <p>
 * L'invio gira su un altro thread (l'esecutore dei compiti di Spring Boot, con i thread virtuali): la risposta HTTP non
 * aspetta Gmail, così chi chiede un link per un'email registrata non aspetta più di chi lo chiede per una che non esiste, e
 * dai tempi non si capisce quale delle due sia. Un errore di Gmail resta nel log e non arriva al client, che ha già la risposta.
 * <p>
 * Senza MAIL_USERNAME o MAIL_PASSWORD non parte niente: lo dice un WARN all'avvio, e l'endpoint risponde comunque.
 */
@Slf4j
@Component
public class EmailSenderSmtp implements EmailSender {

    private final JavaMailSender mailSender;
    private final Executor esecutore;
    private final String mittente;
    private final boolean configurato;

    public EmailSenderSmtp(JavaMailSender mailSender, MailProperties proprieta,
                           @Qualifier("applicationTaskExecutor") Executor esecutore) {
        this.mailSender = mailSender;
        this.esecutore = esecutore;
        this.mittente = proprieta.getUsername();
        this.configurato = presente(proprieta.getUsername()) && presente(proprieta.getPassword());
        if (!configurato) {
            log.warn("Recupero password: MAIL_USERNAME o MAIL_PASSWORD mancanti, le email non partono");
        }
    }

    @Override
    public void invia(String destinatario, String oggetto, String testo) {
        // Il motivo l'ha già detto il WARN dell'avvio: qui non si ripete a ogni richiesta
        if (!configurato) return;
        SimpleMailMessage messaggio = new SimpleMailMessage();
        messaggio.setFrom(mittente);
        messaggio.setTo(destinatario);
        messaggio.setSubject(oggetto);
        messaggio.setText(testo);
        esecutore.execute(() -> spedisci(messaggio, destinatario));
    }

    private void spedisci(SimpleMailMessage messaggio, String destinatario) {
        try {
            mailSender.send(messaggio);
        } catch (MailException e) {
            // Solo il tipo dell'errore (MailAuthenticationException = Gmail rifiuta le credenziali): il messaggio di Gmail può
            // contenere l'indirizzo intero, e lo stack non aggiunge niente che serva
            log.error("Recupero password: email a {} non inviata ({})", mascheraEmail(destinatario), e.getClass().getSimpleName());
        }
    }

    private static boolean presente(String valore) {
        return valore != null && !valore.isBlank();
    }
}
