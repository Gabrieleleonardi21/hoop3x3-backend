package com.hoop3x3.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo schema del database lo creano le migrazioni di Flyway (src/main/resources/db/migration), non uno script a mano:
 * all'avvio del contesto Flyway applica quelle che mancano e Hibernate (ddl-auto=validate) controlla che le entity
 * combacino. Su un database di prova vuoto, come quello della CI, la V1 gira per intero; su uno creato prima di Flyway
 * lo storico la segna come già applicata (baseline-on-migrate).
 */
@TestDiIntegrazione
class MigrazioniIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    void loStoricoDiFlywaySegnaLaV1ComeApplicata() {
        Integer applicate = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);

        assertThat(applicate).isEqualTo(1);
    }

    @Test
    void leMigrazioniCreanoLeTabelleEGliIndiciDelloSchema() {
        assertThat(nomi("select tablename from pg_tables where schemaname = 'public'"))
                .contains("utenti", "refresh_tokens", "leghe", "tappe", "anagrafe_giocatori", "anagrafe_squadre",
                        "anagrafe_squadre_roster", "archivio_tappe");
        // Hibernate controlla tabelle e colonne delle entity ma non gli indici: li controlla questo test
        assertThat(nomi("select indexname from pg_indexes where schemaname = 'public'"))
                .contains("idx_refresh_tokens_utente", "idx_leghe_owner", "idx_tappe_lega",
                        "idx_anagrafe_giocatori_cognome", "idx_archivio_pubblicato");
    }

    // Una tabella che manca dalla TRUNCATE di svuota.sql resterebbe piena tra un test e l'altro. Lo storico di Flyway
    // invece non va mai svuotato: dice quali migrazioni il database ha già, e svuotarlo le farebbe riapplicare
    @Test
    void svuotaSqlElencaTutteLeTabelleDelloSchemaTranneLoStoricoDiFlyway() throws IOException {
        String script = new ClassPathResource("svuota.sql").getContentAsString(StandardCharsets.UTF_8);
        // La TRUNCATE comincia la riga, mentre i commenti che la nominano cominciano con «--»
        Matcher truncate = Pattern.compile("(?ms)^\\s*TRUNCATE\\s+(.+?)\\s+CASCADE").matcher(script);
        assertThat(truncate.find()).as("svuota.sql ha una TRUNCATE ... CASCADE").isTrue();
        List<String> elencate = Arrays.stream(truncate.group(1).split(",")).map(String::strip).toList();

        assertThat(elencate).containsExactlyInAnyOrderElementsOf(nomi(
                "select tablename from pg_tables where schemaname = 'public' and tablename <> 'flyway_schema_history'"));
    }

    /** Il primo campo di ogni riga restituita dalla query, cioè il nome della tabella o dell'indice */
    private List<String> nomi(String sql) {
        return jdbc.queryForList(sql, String.class);
    }
}
