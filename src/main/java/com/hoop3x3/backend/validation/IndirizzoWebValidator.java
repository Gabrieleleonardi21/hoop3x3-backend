package com.hoop3x3.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** La regola di {@link IndirizzoWeb} per un campo di testo. {@link #valido} è la regola, usata anche per gli url dei video. */
public class IndirizzoWebValidator implements ConstraintValidator<IndirizzoWeb, String> {

    /** Quanto può essere lungo un indirizzo: è anche la lunghezza delle colonne logo, website e instagram (migrazione V7) */
    public static final int LUNGHEZZA_MASSIMA = 2048;

    @Override
    public boolean isValid(String valore, ConstraintValidatorContext contesto) {
        return valido(valore);
    }

    /**
     * Vuoto o assente: va bene (il campo è facoltativo). Altrimenti, senza gli spazi ai lati (il servizio li toglie prima di
     * salvare): al massimo LUNGHEZZA_MASSIMA caratteri e http://, https:// oppure un percorso del sito («/» ma non «//» né «/\»)
     */
    public static boolean valido(String valore) {
        if (valore == null) return true;
        String indirizzo = valore.trim();
        if (indirizzo.isEmpty()) return true;
        if (indirizzo.length() > LUNGHEZZA_MASSIMA) return false;
        // Lo schema senza distinguere le maiuscole, come fa il browser (HTTPS:// è https://)
        if (indirizzo.regionMatches(true, 0, "http://", 0, 7) || indirizzo.regionMatches(true, 0, "https://", 0, 8)) return true;
        // Un percorso del sito: «/» seguito da qualcosa che non è un altro «/» o un «\» (quelli portano su un altro host)
        return indirizzo.startsWith("/") && !indirizzo.startsWith("//") && !indirizzo.startsWith("/\\");
    }
}
