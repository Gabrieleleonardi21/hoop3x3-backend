package com.hoop3x3.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo schema del database lo creano le migrazioni di Flyway (src/main/resources/db/migration), non uno script a mano:
 * all'avvio del contesto Flyway applica quelle che mancano e Hibernate (ddl-auto=validate) controlla che le entity
 * combacino. Il database di prova condiviso può essere vuoto, come quello della CI (V1 gira per intero), oppure creato
 * prima di Flyway (V1 viene segnata come già applicata, baseline-on-migrate). Per non dipendere da com'è, i due percorsi
 * si provano su schemi temporanei con la configurazione dell'applicazione, senza toccare lo schema public.
 */
@TestDiIntegrazione
class MigrazioniIT {

    // Tabelle e indici che crea V1. Hibernate controlla tabelle e colonne delle entity ma non gli indici
    private static final List<String> TABELLE_V1 = List.of("utenti", "refresh_tokens", "leghe", "tappe",
            "anagrafe_giocatori", "anagrafe_squadre", "anagrafe_squadre_roster", "archivio_tappe");
    private static final List<String> INDICI_V1 = List.of("idx_refresh_tokens_utente", "idx_leghe_owner",
            "idx_tappe_lega", "idx_anagrafe_giocatori_cognome", "idx_archivio_pubblicato");

    @Autowired JdbcTemplate jdbc;
    // Il bean del contesto: ha la configurazione dell'applicazione (dove stanno le migrazioni, baseline-on-migrate...)
    @Autowired Flyway flyway;

    // Gli schemi temporanei creati dal test in corso: li elimina @AfterEach, anche se il test fallisce
    private final List<String> schemiTemporanei = new ArrayList<>();

    @AfterEach
    void eliminaGliSchemiTemporanei() {
        for (String schema : schemiTemporanei) {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    // Sul database di prova vale sia con SQL (era vuoto) sia con BASELINE (era stato creato prima di Flyway)
    @Test
    void loStoricoDiFlywaySegnaLaV1ComeApplicata() {
        Integer applicate = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);

        assertThat(applicate).isEqualTo(1);
    }

    @Test
    void suUnoSchemaVuotoLeMigrazioniGiranoPerIntero() {
        String schema = nuovoSchema();

        Flyway migrazioni = configurazionePer(schema).load();
        migrazioni.migrate();

        // V1 è stata eseguita davvero (SQL) e non segnata dalla baseline
        assertThat(tipiApplicati(schema, "1")).containsExactly("SQL");
        assertLeMigrazioniDopoLaV1Applicate(migrazioni);
        assertThat(valori("select tablename from pg_tables where schemaname = ?", schema)).containsAll(TABELLE_V1);
        assertThat(valori("select indexname from pg_indexes where schemaname = ?", schema)).containsAll(INDICI_V1);
    }

    @Test
    void unDatabaseFattoAManoPartePerBaselineEPoiRiceveLeMigrazioniSuccessive() {
        String schema = nuovoSchema();
        // Come il database di Gabriele: ci sono le tabelle di V1 ma nessuno storico di Flyway
        configurazionePer(schema).target("1").load().migrate();
        jdbc.execute("drop table " + schema + ".flyway_schema_history");

        Flyway migrazioni = configurazionePer(schema).load();
        migrazioni.migrate();

        // V1 è segnata dalla baseline e non rieseguita: per la versione 1 non c'è nessuna riga SQL
        assertThat(tipiApplicati(schema, "1")).containsExactly("BASELINE");
        assertLeMigrazioniDopoLaV1Applicate(migrazioni);
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

        assertThat(elencate).containsExactlyInAnyOrderElementsOf(valori(
                "select tablename from pg_tables where schemaname = 'public' and tablename <> 'flyway_schema_history'"));
    }

    /** Crea uno schema vuoto con un nome unico e lo segna per l'eliminazione a fine test */
    private String nuovoSchema() {
        String schema = "mig_prova_" + UUID.randomUUID().toString().replace("-", "");
        schemiTemporanei.add(schema);
        jdbc.execute("create schema " + schema);
        return schema;
    }

    /** La configurazione di Flyway dell'applicazione, non i valori predefiniti della libreria, ma su quello schema */
    private FluentConfiguration configurazionePer(String schema) {
        return Flyway.configure().configuration(flyway.getConfiguration()).schemas(schema);
    }

    /** I tipi (SQL, BASELINE...) con cui lo storico di quello schema ha segnato la versione, solo le righe riuscite */
    private List<String> tipiApplicati(String schema, String versione) {
        return valori("select type from " + schema + ".flyway_schema_history where version = ? and success", versione);
    }

    /** Ogni migrazione dopo V1 risulta applicata con successo. Oggi non ce ne sono: V2, V3... entreranno da sole */
    private static void assertLeMigrazioniDopoLaV1Applicate(Flyway migrazioni) {
        for (MigrationInfo migrazione : migrazioni.info().all()) {
            if (migrazione.isVersioned() && migrazione.getVersion().isNewerThan("1")) {
                assertThat(migrazione.getState()).as("migrazione %s", migrazione.getScript())
                        .isEqualTo(MigrationState.SUCCESS);
            }
        }
    }

    /** Il primo campo di ogni riga restituita dalla query, per esempio il nome della tabella o dell'indice */
    private List<String> valori(String sql, Object... parametri) {
        return jdbc.queryForList(sql, String.class, parametri);
    }
}
