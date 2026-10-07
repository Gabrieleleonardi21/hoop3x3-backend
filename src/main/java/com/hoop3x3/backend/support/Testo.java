package com.hoop3x3.backend.support;

/** Aiuti per i campi di testo scritti dall'utente. */
public final class Testo {

    private Testo() {}

    /** Un campo facoltativo come si salva: assente diventa vuoto (le colonne sono NOT NULL DEFAULT ''), senza spazi ai lati */
    public static String ripulito(String s) {
        if (s == null) return "";
        return s.trim();
    }
}
