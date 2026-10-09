package com.hoop3x3.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        // Un database creato a mano, prima di Flyway: ci sono le tabelle di V1 ma nessuno storico di Flyway
        configurazionePer(schema).target("1").load().migrate();
        jdbc.execute("drop table " + schema + ".flyway_schema_history");

        Flyway migrazioni = configurazionePer(schema).load();
        migrazioni.migrate();

        // V1 è segnata dalla baseline e non rieseguita: per la versione 1 non c'è nessuna riga SQL
        assertThat(tipiApplicati(schema, "1")).containsExactly("BASELINE");
        assertLeMigrazioniDopoLaV1Applicate(migrazioni);
    }

    // Un database già in uso può avere pubblicazioni orfane: la tappa è stata eliminata quando la V1 non lo impediva e non le
    // cancellava. La V2 non deve fallire per colpa loro né cancellarle (toglierebbe dati senza che nessuno l'abbia deciso):
    // il vincolo è NOT VALID, quindi non controlla le righe che ci sono già ma controlla quelle nuove
    @Test
    void laV2LasciaLePubblicazioniOrfaneEImpedisceLeNuove() {
        String schema = nuovoSchema();
        configurazionePer(schema).target("1").load().migrate();
        UUID autore = nuovoUtente(schema);
        UUID orfana = UUID.randomUUID();
        nuovaPubblicazione(schema, orfana, autore); // con la V1 nulla impedisce una pubblicazione senza tappa

        // Si ferma alla V2: il test riguarda lei, e le righe di prova hanno i soli campi obbligatori di quello schema. Una
        // migrazione futura sulle stesse tabelle non deve romperlo per un motivo che con la V2 non c'entra
        configurazionePer(schema).target("2").load().migrate();

        // L'orfana è ancora lì, e la query che la cerca la trova
        assertThat(valori("select tappa_id::text from " + schema + ".archivio_tappe")).containsExactly(orfana.toString());
        assertThat(orfane(schema)).containsExactly(orfana.toString());
        // Una pubblicazione nuova deve avere la sua tappa
        assertThatThrownBy(() -> nuovaPubblicazione(schema, UUID.randomUUID(), autore))
                .isInstanceOf(DataIntegrityViolationException.class);
        nuovaPubblicazione(schema, nuovaTappa(schema, autore), autore);
        assertThat(orfane(schema)).containsExactly(orfana.toString());
        // Chi cancella le orfane può convalidare il vincolo anche sulle righe vecchie
        jdbc.update("delete from " + schema + ".archivio_tappe where tappa_id = ?", orfana);
        jdbc.execute("alter table " + schema + ".archivio_tappe validate constraint archivio_tappe_tappa_id_fkey");
    }

    // La colonna versione delle tappe è NOT NULL: la V4 non deve fallire su un database che ha già delle tappe, né lasciarle senza
    // valore. Le tappe già salvate partono dalla versione 0, come una tappa nuova, e così un inserimento che non nomina la colonna
    // (i test con JDBC, un import a mano). Si ferma alla V3 per avere una tappa «di prima»
    @Test
    void laV4DaVersioneZeroAlleTappeGiaSalvateEAQuelleInseriteSenzaNominarla() {
        String schema = nuovoSchema();
        configurazionePer(schema).target("3").load().migrate();
        UUID proprietario = nuovoUtente(schema);
        nuovaTappa(schema, proprietario);

        configurazionePer(schema).target("4").load().migrate();
        nuovaTappa(schema, proprietario);

        assertThat(valori("select versione::text from " + schema + ".tappe")).containsExactly("0", "0");
        assertThatThrownBy(() -> jdbc.update("update " + schema + ".tappe set versione = null"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Come la V4 per le tappe: le schede dell'anagrafe già salvate partono dalla versione 0, e così una riga inserita senza
    // nominare la colonna. Si ferma alla V4 per avere una scheda «di prima»
    @Test
    void laV5DaVersioneZeroAlleSchedeGiaSalvateEAQuelleInseriteSenzaNominarla() {
        String schema = nuovoSchema();
        configurazionePer(schema).target("4").load().migrate();
        UUID autore = nuovoUtente(schema);
        nuovoGiocatore(schema, autore);
        nuovaSquadra(schema, autore);

        configurazionePer(schema).target("5").load().migrate();
        nuovoGiocatore(schema, autore);
        nuovaSquadra(schema, autore);

        assertThat(valori("select versione::text from " + schema + ".anagrafe_giocatori")).containsExactly("0", "0");
        assertThat(valori("select versione::text from " + schema + ".anagrafe_squadre")).containsExactly("0", "0");
    }

    // La V6 riempie le colonne dell'elenco (nome, luogo, data, numero di squadre) dal contenuto delle pubblicazioni già presenti,
    // con le stesse regole che usava la query dell'elenco: le pubblicazioni del vecchio endpoint avevano la tappa scelta dal
    // client, quindi campi mancanti o nulli diventano vuoti, squadre che non sono un array contano 0, un contenuto che non è
    // un oggetto dà vuoti e 0, e un nome più lungo della colonna si tronca invece di far fallire la migrazione. Si ferma alla
    // V5 per avere le pubblicazioni «di prima»
    @Test
    void laV6RiempieLeColonneDellElencoDalContenutoDellePubblicazioniGiaPresenti_ancheConContenutiStrani() {
        String schema = nuovoSchema();
        configurazionePer(schema).target("5").load().migrate();
        UUID autore = nuovoUtente(schema);
        Map<String, String> contenuti = new LinkedHashMap<>();
        contenuti.put("completa", "{\"nome\": \"Tappa di Roma\", \"luogo\": \"Roma\", \"data\": \"2026-06-14\", \"squadre\": [{}, {}, {}]}");
        contenuti.put("senza campi", "{\"nome\": \"Vecchia\"}");
        contenuti.put("campi nulli", "{\"nome\": \"Vecchia\", \"luogo\": null, \"data\": null, \"squadre\": null}");
        contenuti.put("squadre oggetto", "{\"nome\": \"Vecchia\", \"squadre\": {\"s1\": \"Team Rome\"}}");
        contenuti.put("squadre testo", "{\"nome\": \"Vecchia\", \"squadre\": \"nessuna\"}");
        contenuti.put("vuota", "{}");
        contenuti.put("array", "[]");
        contenuti.put("nome lungo", "{\"nome\": \"" + "x".repeat(200) + "\"}");
        Map<String, UUID> id = new LinkedHashMap<>();
        contenuti.forEach((caso, contenuto) -> id.put(caso, nuovaPubblicazione(schema, nuovaTappa(schema, autore), autore, contenuto)));

        configurazionePer(schema).target("6").load().migrate();

        assertThat(voceDellElenco(schema, id.get("completa"))).containsExactly("Tappa di Roma", "Roma", "2026-06-14", "3");
        for (String caso : List.of("senza campi", "campi nulli", "squadre oggetto", "squadre testo")) {
            assertThat(voceDellElenco(schema, id.get(caso))).as(caso).containsExactly("Vecchia", "", "", "0");
        }
        assertThat(voceDellElenco(schema, id.get("vuota"))).containsExactly("", "", "", "0");
        assertThat(voceDellElenco(schema, id.get("array"))).containsExactly("", "", "", "0");
        assertThat(voceDellElenco(schema, id.get("nome lungo"))).containsExactly("x".repeat(120), "", "", "0");
        assertThat(valori("select indexname from pg_indexes where schemaname = ?", schema))
                .contains("idx_anagrafe_squadre_roster_giocatore");
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

    /** Ogni migrazione dopo V1 (V2, V3...) risulta applicata con successo: una nuova entra da sola, senza ritoccare i test */
    private static void assertLeMigrazioniDopoLaV1Applicate(Flyway migrazioni) {
        for (MigrationInfo migrazione : migrazioni.info().all()) {
            if (migrazione.isVersioned() && migrazione.getVersion().isNewerThan("1")) {
                assertThat(migrazione.getState()).as("migrazione %s", migrazione.getScript())
                        .isEqualTo(MigrationState.SUCCESS);
            }
        }
    }

    /* ── Righe di prova nello schema temporaneo (con i soli campi obbligatori) ── */

    private UUID nuovoUtente(String schema) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into " + schema + ".utenti (id, email, password, nome, ruolo, creato_il, modificato_il) "
                + "values (?, 'mario@test.it', 'hash', 'Mario', 'USER', now(), now())", id);
        return id;
    }

    /** Una lega con una tappa dell'utente: restituisce l'id della tappa */
    private UUID nuovaTappa(String schema, UUID proprietario) {
        UUID lega = UUID.randomUUID();
        UUID tappa = UUID.randomUUID();
        jdbc.update("insert into " + schema + ".leghe (id, nome, owner_id, creato_il, modificato_il) "
                + "values (?, 'Circuito', ?, now(), now())", lega, proprietario);
        jdbc.update("insert into " + schema + ".tappe (id, lega_id, nome, creato_il, modificato_il) "
                + "values (?, ?, 'Tappa', now(), now())", tappa, lega);
        return tappa;
    }

    private void nuovoGiocatore(String schema, UUID autore) {
        jdbc.update("insert into " + schema + ".anagrafe_giocatori (id, nome, cognome, autore_id, creato_il, modificato_il) "
                + "values (?, 'Mario', 'Rossi', ?, now(), now())", UUID.randomUUID(), autore);
    }

    private void nuovaSquadra(String schema, UUID autore) {
        jdbc.update("insert into " + schema + ".anagrafe_squadre (id, nome, autore_id, creato_il, modificato_il) "
                + "values (?, 'Roma 3x3', ?, now(), now())", UUID.randomUUID(), autore);
    }

    private void nuovaPubblicazione(String schema, UUID tappa, UUID autore) {
        nuovaPubblicazione(schema, tappa, autore, "{}");
    }

    /** Una pubblicazione con il contenuto JSON scelto dal chiamante: restituisce l'id della tappa */
    private UUID nuovaPubblicazione(String schema, UUID tappa, UUID autore, String contenuto) {
        jdbc.update("insert into " + schema + ".archivio_tappe (tappa_id, lega_nome, autore_id, contenuto, pubblicato_il) "
                + "values (?, 'Circuito', ?, ?::jsonb, now())", tappa, autore, contenuto);
        return tappa;
    }

    /** Le colonne dell'elenco (V6) di una pubblicazione: nome, luogo, data e numero di squadre, come testo */
    private List<String> voceDellElenco(String schema, UUID tappa) {
        return jdbc.queryForList("select nome, luogo, data, numero_squadre::text from " + schema + ".archivio_tappe where tappa_id = ?",
                        tappa).stream().flatMap(riga -> riga.values().stream()).map(String::valueOf).toList();
    }

    /** Le pubblicazioni la cui tappa non esiste più: la query del README per i database già in uso, sullo schema di prova */
    private List<String> orfane(String schema) {
        return valori("select a.tappa_id::text from " + schema + ".archivio_tappe a "
                + "where not exists (select 1 from " + schema + ".tappe t where t.id = a.tappa_id)");
    }

    /** Il primo campo di ogni riga restituita dalla query, per esempio il nome della tabella o dell'indice */
    private List<String> valori(String sql, Object... parametri) {
        return jdbc.queryForList(sql, String.class, parametri);
    }
}
