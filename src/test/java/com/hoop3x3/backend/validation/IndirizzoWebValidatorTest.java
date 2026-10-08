package com.hoop3x3.backend.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** La regola degli indirizzi web (IndirizzoWeb), senza contesto Spring: la stessa di safeUrl nel frontend. */
class IndirizzoWebValidatorTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "https://roma3x3.it", "http://roma3x3.it/logo.png", "  https://roma3x3.it  ",
            "HTTPS://roma3x3.it", "/logos/roma.svg", "/"})
    void vuotoHttpOPercorsoDelSito_valido(String indirizzo) {
        assertThat(IndirizzoWebValidator.valido(indirizzo)).isTrue();
    }

    // Gli schemi che eseguono codice o portano dati, un indirizzo senza schema, i percorsi «protocol-relative» (un altro
    // host) e uno schema con dentro un carattere di controllo, che il browser normalizzerebbe in javascript:
    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "data:text/html,ciao", "vbscript:x", "ftp://roma3x3.it", "roma3x3.it",
            "www.roma3x3.it", "//evil.example/x", "/\\evil.example", "java\tscript:alert(1)", "mailto:a@b.it"})
    void altriSchemiOSenzaSchema_nonValido(String indirizzo) {
        assertThat(IndirizzoWebValidator.valido(indirizzo)).isFalse();
    }

    // Il confine esatto: 2048 caratteri vanno bene, 2049 no
    @Test
    void lunghezza_2048SiAccetta_2049No() {
        String base = "https://roma3x3.it/";
        assertThat(IndirizzoWebValidator.valido(base + "x".repeat(2048 - base.length()))).isTrue();
        assertThat(IndirizzoWebValidator.valido(base + "x".repeat(2049 - base.length()))).isFalse();
    }
}
