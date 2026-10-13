package com.hoop3x3.backend.services;

/**
 * Chi manda le email dell'app (il link del recupero password e quello di conferma del cambio email). È un'interfaccia perché i test la sostituiscono con
 * una finta che le ricorda invece di mandarle; quella vera è EmailSenderBrevo (l'API HTTP di Brevo).
 */
public interface EmailSender {

    /**
     * Un'email in HTML con il testo semplice come riserva, per i client che non mostrano l'HTML. Non deve far aspettare il
     * chiamante l'invio vero
     */
    void invia(String destinatario, String oggetto, String testo, String html);
}
