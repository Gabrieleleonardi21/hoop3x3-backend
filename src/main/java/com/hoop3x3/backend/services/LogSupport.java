package com.hoop3x3.backend.services;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aiuto per i valori scritti da chi manda la richiesta (email, percorso...) prima che finiscano in una riga di log. Una
 * sola strategia per tutti: la validazione da sola non basta, perché per esempio @Email rifiuta CR e LF ma lascia
 * passare i separatori di riga di Unicode.
 */
public final class LogSupport {

    /** Caratteri di controllo (CR, LF, ESC, U+0085...) e separatori di riga e di paragrafo di Unicode (U+2028, U+2029) */
    private static final Pattern DA_SCRIVERE_PER_ESTESO = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

    private LogSupport() {}

    /**
     * Il valore con a capo, separatori di riga e caratteri di controllo scritti per esteso (\r e \n, gli altri come
     * \ seguito da u e dal codice): chi sceglie il valore non può chiudere la riga e inventarne una sua, e il tentativo
     * resta visibile nei log.
     */
    public static String perLog(String valore) {
        if (valore == null) return null;
        // quoteReplacement: nel testo che sostituisce la barra rovescia è un carattere speciale
        return DA_SCRIVERE_PER_ESTESO.matcher(valore)
                .replaceAll(trovato -> Matcher.quoteReplacement(perEsteso(trovato.group().charAt(0))));
    }

    private static String perEsteso(char carattere) {
        return switch (carattere) {
            case '\r' -> "\\r";
            case '\n' -> "\\n";
            default -> String.format("\\u%04x", (int) carattere);
        };
    }
}
