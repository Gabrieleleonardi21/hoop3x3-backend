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

    /**
     * Il corpo della risposta di un fornitore (Groq, Anthropic) per i log: al massimo 500 caratteri, con «…» se ne aveva di più.
     * Si tronca prima di passare da perLog, così una sequenza di escape non resta tagliata a metà
     */
    public static String tronca(String s) {
        if (s == null) return "";
        if (s.length() <= 500) return s;
        return s.substring(0, 500) + "…";
    }

    /**
     * L'email per il log del recupero password: le prime due lettere e il dominio (mario@x.it diventa ma***@x.it). Basta a
     * capire di chi si parla senza scrivere l'indirizzo intero; passa comunque da perLog, perché la sceglie chi chiede il link
     */
    public static String mascheraEmail(String email) {
        int chiocciola = email.lastIndexOf('@');
        if (chiocciola < 0) return "***";
        return perLog(email.substring(0, Math.min(2, chiocciola)) + "***" + email.substring(chiocciola));
    }

    private static String perEsteso(char carattere) {
        return switch (carattere) {
            case '\r' -> "\\r";
            case '\n' -> "\\n";
            default -> String.format("\\u%04x", (int) carattere);
        };
    }
}
