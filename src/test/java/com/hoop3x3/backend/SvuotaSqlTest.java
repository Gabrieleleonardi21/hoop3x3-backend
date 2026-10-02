package com.hoop3x3.backend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * svuota.sql deve proteggere il database vero anche quando qualcuno lo lancia a mano con psql, che dopo un errore
 * passa all'istruzione successiva: per questo la TRUNCATE deve stare nello stesso blocco della guardia.
 */
class SvuotaSqlTest {

    @Test
    void laTruncateStaNelloStessoBloccoDellaGuardia() throws IOException {
        String script = new ClassPathResource("svuota.sql").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "")
                .strip();

        // Un solo blocco DO: se la guardia fallisce non resta nessuna istruzione che psql possa eseguire dopo
        assertThat(script).startsWith("DO $$").endsWith("END $$;");
        assertThat(script.split("\\$\\$", -1)).hasSize(3);
        // Dentro il blocco il controllo sul nome del database viene prima della cancellazione
        assertThat(script.indexOf("RAISE EXCEPTION")).isPositive().isLessThan(script.indexOf("TRUNCATE"));
    }
}
