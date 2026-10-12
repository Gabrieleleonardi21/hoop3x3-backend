package com.hoop3x3.backend.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

import static com.hoop3x3.backend.services.LogSupport.mascheraEmail;

/**
 * Le email vere, con l'API HTTP di Brevo (porta 443): su Render, piano gratuito, le porte SMTP (25, 465, 587) sono bloccate e
 * Gmail SMTP non partiva. Una POST a /v3/smtp/email con la chiave BREVO_API_KEY e il mittente MAIL_USERNAME, un indirizzo
 * verificato in Brevo.
 * <p>
 * L'invio gira su un altro thread (l'esecutore dei compiti di Spring Boot, con i thread virtuali): la risposta HTTP non aspetta
 * Brevo, così chi chiede un link per un'email registrata non aspetta più di chi lo chiede per una che non esiste, e dai tempi
 * non si capisce quale delle due sia. Un errore di Brevo resta nel log (stato e tipo dell'errore, mai il corpo della risposta né
 * la chiave) e non arriva al client, che ha già la risposta.
 * <p>
 * Senza chiave o senza mittente non parte niente: lo dice un WARN all'avvio, e l'endpoint risponde comunque.
 */
@Slf4j
@Component
@EnableConfigurationProperties(AppProperties.class)
public class EmailSenderBrevo implements EmailSender {

    /** L'indirizzo dell'API delle email transazionali di Brevo */
    static final String URL_BREVO = "https://api.brevo.com/v3/smtp/email";
    static final String NOME_MITTENTE = "HOOP 3X3";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Il corpo che Brevo si aspetta: mittente con il nome, destinatari (solo l'email: un «name» null Brevo lo rifiuterebbe),
     *  oggetto, HTML e testo semplice (la riserva per i client che non mostrano l'HTML) */
    private record Mittente(String email, String name) {}
    private record Destinatario(String email) {}
    private record Corpo(Mittente sender, List<Destinatario> to, String subject, String htmlContent, String textContent) {}

    private final String url;
    private final String apiKey;
    private final String mittente;
    private final Executor esecutore;
    private final RestClient http;
    private final boolean configurato;

    @Autowired
    public EmailSenderBrevo(AppProperties proprieta, @Qualifier("applicationTaskExecutor") Executor esecutore) {
        this(URL_BREVO, proprieta.brevo().apiKey(), proprieta.mail().mittente(), TIMEOUT, esecutore);
    }

    /** Per i test: Brevo finto su un indirizzo locale e timeout brevi */
    EmailSenderBrevo(String url, String apiKey, String mittente, Duration timeout, Executor esecutore) {
        this.url = url;
        this.apiKey = apiKey;
        this.mittente = mittente;
        this.esecutore = esecutore;
        // Senza timeout un Brevo lento terrebbe occupato il thread dell'invio per sempre
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
        this.configurato = presente(apiKey) && presente(mittente);
        if (!presente(apiKey)) {
            log.warn("Recupero password: BREVO_API_KEY mancante, le email non partono");
        } else if (!presente(mittente)) {
            log.warn("Recupero password: MAIL_USERNAME mancante (il mittente), le email non partono");
        }
    }

    @Override
    public void invia(String destinatario, String oggetto, String testo, String html) {
        // Il motivo l'ha già detto il WARN dell'avvio: qui non si ripete a ogni richiesta
        if (!configurato) return;
        Corpo corpo = new Corpo(new Mittente(mittente, NOME_MITTENTE), List.of(new Destinatario(destinatario)), oggetto, html,
                testo);
        esecutore.execute(() -> spedisci(corpo, destinatario));
    }

    private void spedisci(Corpo corpo, String destinatario) {
        try {
            http.post()
                    .uri(url)
                    .header("api-key", apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corpo)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // Solo lo stato (401 = chiave sbagliata, 400 = per esempio mittente non verificato) e il tipo: il corpo di Brevo può
            // contenere il mittente o altri dati dell'account
            log.error("Recupero password: email a {} non inviata, Brevo ha risposto {} ({})", mascheraEmail(destinatario),
                    e.getStatusCode().value(), e.getClass().getSimpleName());
        } catch (RestClientException e) {
            // Timeout o rete: il messaggio dell'eccezione porta l'indirizzo chiamato, nel log va solo il tipo
            log.error("Recupero password: email a {} non inviata, Brevo non raggiungibile ({})", mascheraEmail(destinatario),
                    e.getClass().getSimpleName());
        }
    }

    private static boolean presente(String valore) {
        return valore != null && !valore.isBlank();
    }
}
