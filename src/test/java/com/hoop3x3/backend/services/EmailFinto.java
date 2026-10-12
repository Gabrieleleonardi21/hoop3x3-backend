package com.hoop3x3.backend.services;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EmailSender finto: ricorda le email invece di mandarle. Nei test unitari si passa al servizio; nei test di integrazione
 * prende il posto di quello SMTP con {@link Configurazione} (@Import), così nessun test manda email vere.
 */
public class EmailFinto implements EmailSender {

    public record Email(String destinatario, String oggetto, String testo) {}

    // Il token nel link dell'email: Base64 URL, senza padding
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private final List<Email> inviate = new CopyOnWriteArrayList<>();

    @Override
    public void invia(String destinatario, String oggetto, String testo) {
        inviate.add(new Email(destinatario, oggetto, testo));
    }

    public List<Email> inviate() {
        return List.copyOf(inviate);
    }

    /** Il token del link dell'ultima email inviata */
    public String ultimoToken() {
        Matcher trovato = TOKEN.matcher(inviate.getLast().testo());
        if (!trovato.find()) throw new IllegalStateException("L'ultima email non contiene un link con il token");
        return trovato.group(1);
    }

    public void svuota() {
        inviate.clear();
    }

    /** Registra l'EmailSender finto come quello principale del contesto */
    @TestConfiguration
    public static class Configurazione {
        @Bean
        @Primary
        public EmailFinto emailFinto() {
            return new EmailFinto();
        }
    }
}
