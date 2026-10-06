package com.hoop3x3.backend.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * I valori scritti da chi manda la richiesta (email, percorso) non devono poter chiudere una riga di log e inventarne
 * una sua: la validazione ferma solo una parte dei casi (@Email rifiuta CR e LF ma lascia passare i separatori di riga di
 * Unicode), quindi prima di scriverli si tolgono a capo e caratteri di controllo.
 */
class LogSupportTest {

    // Ogni carattere che può chiudere una riga (o muovere il cursore di un terminale) si scrive per esteso, così il
    // tentativo resta visibile nei log. Le forme brevi \r e \n sono quelle che si leggono meglio
    static Stream<Arguments> caratteriDaScrivereIPerEsteso() {
        return Stream.of(
                arguments('\r', "\\r"),
                arguments('\n', "\\n"),
                arguments('\u2028', "\\u2028"), // separatore di riga di Unicode
                arguments('\u2029', "\\u2029"), // separatore di paragrafo
                arguments('\u0085', "\\u0085"), // «riga successiva»: un carattere di controllo
                arguments('\u001b', "\\u001b"), // ESC: apre le sequenze di controllo di un terminale
                arguments('\t', "\\u0009"),
                arguments('\0', "\\u0000"),
                arguments('\u007f', "\\u007f"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("caratteriDaScrivereIPerEsteso")
    void ilCarattereSiScrivePerEsteso(char carattere, String perEsteso) {
        assertThat(LogSupport.perLog("a" + carattere + "b")).isEqualTo("a" + perEsteso + "b");
    }

    @Test
    void piuCaratteriInsieme_sonoTuttiScrittiPerEsteso() {
        String risultato = LogSupport.perLog("mario@x.it\r\nINFO riga\u2028inventata\u0085");

        assertThat(risultato).isEqualTo("mario@x.it\\r\\nINFO riga\\u2028inventata\\u0085");
        assertThat(risultato).doesNotContainPattern("[\\p{Cc}\\p{Zl}\\p{Zp}]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mario.rossi+prova@x.it", "GET /api/leghe/12", "Estathé – città 3x3 💥", "", "con spazi  doppi"})
    void unTestoComune_restaCom_era(String testo) {
        assertThat(LogSupport.perLog(testo)).isEqualTo(testo);
    }

    @Test
    void ilValoreNullo_restaNullo() {
        assertThat(LogSupport.perLog(null)).isNull();
    }
}
