package com.hoop3x3.backend.exceptions;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Il messaggio del 429 dice quanto aspettare a parole: «42 secondi» ma anche «14 ore», non «50380 secondi» */
class TroppeRichiesteExceptionTest {

    // L'attesa si arrotonda per eccesso: chi aspetta quanto gli è stato detto non ritrova il limite
    @ParameterizedTest(name = "{0} secondi: «riprova tra {1}»")
    @CsvSource({
            "1, 1 secondo",
            "42, 42 secondi",
            "59, 59 secondi",
            "60, 1 minuto",
            "61, 2 minuti",
            "3540, 59 minuti",
            "3541, 1 ora",     // 59 minuti e 1 secondo: 60 minuti per eccesso, cioè un'ora
            "3600, 1 ora",
            "3601, 2 ore",
            "50380, 14 ore",   // 13 ore, 59 minuti e 40 secondi
            "86400, 24 ore"
    })
    void ilMessaggioDiceQuantoAspettare(long secondi, String attesa) {
        TroppeRichiesteException eccezione = new TroppeRichiesteException("Troppi tentativi di accesso", secondi);

        assertThat(eccezione.getMessage()).isEqualTo("Troppi tentativi di accesso: riprova tra " + attesa);
        assertThat(eccezione.getSecondiAttesa()).as("i secondi esatti vanno nel Retry-After").isEqualTo(secondi);
    }
}
