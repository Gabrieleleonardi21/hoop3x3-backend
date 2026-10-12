package com.hoop3x3.backend.services;

/**
 * Chi manda le email dell'app (oggi solo il link del recupero password). È un'interfaccia perché i test la sostituiscono con
 * una finta che le ricorda invece di mandarle; quella vera è EmailSenderBrevo (l'API HTTP di Brevo).
 */
public interface EmailSender {

    /** Un'email di testo semplice. Non deve far aspettare il chiamante l'invio vero */
    void invia(String destinatario, String oggetto, String testo);
}
