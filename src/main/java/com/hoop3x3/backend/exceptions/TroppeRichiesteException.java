package com.hoop3x3.backend.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Un limite di frequenza è stato superato (login, registrazione, rinnovo del token, Coach AI). Il messaggio dice a parole
 * quanto aspettare («riprova tra 40 secondi»); i secondi esatti vanno nell'intestazione Retry-After (vedi ExceptionsHandler).
 */
@Getter
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class TroppeRichiesteException extends RuntimeException {

    private final long secondiAttesa;

    public TroppeRichiesteException(String motivo, long secondiAttesa) {
        super(motivo + ": riprova tra " + attesaInParole(secondiAttesa));
        this.secondiAttesa = secondiAttesa;
    }

    /**
     * «1 secondo», «42 secondi», «5 minuti», «14 ore»: una quota giornaliera può fare aspettare 50000 secondi, che a leggerli
     * non dicono niente. Si arrotonda per eccesso, così chi aspetta quanto gli è stato detto non ritrova il limite.
     */
    private static String attesaInParole(long secondi) {
        if (secondi < 60) return unita(secondi, "secondo", "secondi");
        long minuti = (secondi + 59) / 60;
        if (minuti < 60) return unita(minuti, "minuto", "minuti");
        return unita((minuti + 59) / 60, "ora", "ore");
    }

    private static String unita(long quante, String singolare, String plurale) {
        if (quante == 1) return "1 " + singolare;
        return quante + " " + plurale;
    }
}
