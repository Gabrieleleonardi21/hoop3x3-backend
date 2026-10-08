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
     * salvare): al massimo LUNGHEZZA_MASSIMA caratteri, nessun carattere di controllo o spazio dentro, e http://, https://
     * oppure un percorso del sito («/» ma non «//» né «/\»). I caratteri di controllo dentro l'indirizzo il browser li toglie
     * prima di leggerlo: «/<tab>/host» diventa «//host», un altro sito, e «java<a capo>script:» diventa «javascript:»
     */
    public static boolean valido(String valore) {
        if (valore == null) return true;
        String indirizzo = valore.trim();
        if (indirizzo.isEmpty()) return true;
        if (indirizzo.length() > LUNGHEZZA_MASSIMA) return false;
        for (int i = 0; i < indirizzo.length(); i++) {
            char c = indirizzo.charAt(i);
            if (c <= ' ' || c == 0x7f) return false;
        }
        if (iniziaCon(indirizzo, "http://") || iniziaCon(indirizzo, "https://")) return true;
        // Un percorso del sito: «/» seguito da qualcosa che non è un altro «/» o un «\» (quelli portano su un altro host)
        return indirizzo.startsWith("/") && !indirizzo.startsWith("//") && !indirizzo.startsWith("/\\");
    }

    /**
     * Se l'indirizzo comincia con lo schema, senza distinguere le maiuscole ma solo tra lettere ASCII, come fa il browser:
     * regionMatches(true, …) userebbe le maiuscole di Unicode, per cui «ſ» (s lunga) varrebbe come «s» e «httpſ://» passerebbe
     */
    private static boolean iniziaCon(String indirizzo, String schema) {
        if (indirizzo.length() < schema.length()) return false;
        for (int i = 0; i < schema.length(); i++) {
            char c = indirizzo.charAt(i);
            if (c > 127 || Character.toLowerCase(c) != schema.charAt(i)) return false;
        }
        return true;
    }
}
