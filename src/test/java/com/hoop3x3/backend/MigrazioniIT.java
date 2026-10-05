package com.hoop3x3.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

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

    /** Il primo campo di ogni riga restituita dalla query, cioè il nome della tabella o dell'indice */
    private List<String> nomi(String sql) {
        return jdbc.queryForList(sql, String.class);
    }
}
