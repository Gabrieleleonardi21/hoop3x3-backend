package com.hoop3x3.backend.runners;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.PubTappaDTO;
import com.hoop3x3.backend.dto.PubTappaMetaDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import com.hoop3x3.backend.repositories.LegaRepository;
import com.hoop3x3.backend.repositories.TappaRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.LegaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il seed demo con il database vero: le 4 tappe del circuito Estathé finiscono in archivio con la forma che il frontend
 * legge dalle API delle tappe, intestate all'admin. Il seeder parte da solo solo all'avvio (SEED_DEMO=true) e i test
 * cominciano con le tabelle vuote: qui lo si accende a mano, dopo aver creato l'admin. Il seed si esegue una sola volta,
 * ricordato dal segno nella tabella seed_eseguiti (vedi SeedEseguito).
 */
@TestDiIntegrazione
class DemoSeederIT {

    @Autowired DemoSeeder seeder;
    @Autowired UtenteRepository utenti;
    @Autowired LegaRepository leghe;
    @Autowired TappaRepository tappe;
    @Autowired AnagrafeGiocatoreRepository giocatori;
    @Autowired AnagrafeSquadraRepository squadre;
    @Autowired JdbcTemplate jdbc;
    @Autowired ArchivioService archivioService;
    @Autowired LegaService legaService;
    @Autowired ObjectMapper mapper;

    // Il seeder è un bean condiviso con gli altri test di integrazione: lo si rimette come lo vuole il profilo di prova,
    // spento (seed.demo=false e nessuna email dell'admin, vedi application-test.properties)
    @AfterEach
    void spegniIlSeeder() {
        ReflectionTestUtils.setField(seeder, "abilitato", false);
        ReflectionTestUtils.setField(seeder, "adminEmail", "");
    }

    @Test
    void leTappeDelSeedSonoInArchivioUgualiAQuelleSalvateEIntestateAllAdmin() throws Exception {
        Utente admin = accendiIlSeed();

        seeder.run();

        String nomeLega = leghe.findAll().getFirst().getNome();
        List<PubTappaMetaDTO> elenco = archivioService.tutte();
        assertThat(elenco).hasSize(4).allSatisfy(voce -> {
            assertThat(voce.lega()).isEqualTo(nomeLega);
            assertThat(voce.autore()).isEqualTo("Admin");
            // L'elenco è sintetico: autore e tappa si guardano nella pubblicazione, come fa l'app aprendola
            PubTappaDTO pubblicata = archivioService.una(voce.tappaId());
            assertThat(pubblicata.autoreId()).isEqualTo(admin.getId());
            assertThat(pubblicata.lega()).isEqualTo(nomeLega);
            // Come nell'archivio dell'app: la tappa salvata nel database, nella forma delle API
            TappaDTO salvata = legaService.toDto(tappe.findById(voce.tappaId()).orElseThrow());
            assertThat(comeJson(pubblicata.tappa())).isEqualTo(comeJson(salvata));
        });
    }

    @Test
    void alTermineDelSeedIlSegnoStaNelDatabase() throws Exception {
        accendiIlSeed();
        assertThat(segni()).as("prima del seed nessun segno").isEmpty();

        seeder.run();

        assertThat(segni()).containsExactly("demo");
        assertThat(jdbc.queryForObject("select count(*) from seed_eseguiti where eseguito_il is not null", Integer.class))
                .as("il segno ha la data").isEqualTo(1);
    }

    // Il difetto di BE-17 (la spiegazione è in SeedEseguito): senza il segno, dopo l'eliminazione della lega demo il
    // riavvio inseriva giocatori e squadre una seconda volta
    @Test
    void seLaLegaDemoEEliminata_alRiavvioNonCiSonoGiocatoriNeSquadreDoppi() throws Exception {
        accendiIlSeed();
        seeder.run();
        long giocatoriDemo = giocatori.count();
        long squadreDemo = squadre.count();
        assertThat(giocatoriDemo).isPositive();
        assertThat(squadreDemo).isPositive();
        // Un task successivo cambierà i nomi dei dati demo: il segno non deve dipendere da nessuno di loro
        jdbc.update("update anagrafe_giocatori set nome = 'Nome', cognome = 'Cognome'");
        jdbc.update("update anagrafe_squadre set nome = 'Squadra'");
        jdbc.update("update leghe set nome = 'Lega'");

        leghe.deleteAll();
        assertThat(tappe.count()).as("con la lega spariscono le tappe").isZero();
        assertThat(archivioService.tutte()).as("e l'archivio").isEmpty();
        assertThat(giocatori.count()).as("ma non i giocatori").isEqualTo(giocatoriDemo);

        seeder.run(); // il riavvio

        assertThat(giocatori.count()).isEqualTo(giocatoriDemo);
        assertThat(squadre.count()).isEqualTo(squadreDemo);
        assertThat(leghe.count()).as("la lega demo non rinasce").isZero();
    }

    // I database seminati prima del segno: i dati ci sono (compresa la prima tappa demo) ma il segno no. Il seed non riparte
    // e il segno compare
    @Test
    void databaseSeminatoPrimaDelSegno_ilSeedNonRipartEIlSegnoCompare() throws Exception {
        accendiIlSeed();
        seeder.run();
        long giocatoriDemo = giocatori.count();
        long squadreDemo = squadre.count();
        long tappeDemo = tappe.count();
        jdbc.update("delete from seed_eseguiti"); // com'era un database di prima del segno

        seeder.run();

        assertThat(giocatori.count()).isEqualTo(giocatoriDemo);
        assertThat(squadre.count()).isEqualTo(squadreDemo);
        assertThat(tappe.count()).isEqualTo(tappeDemo);
        assertThat(segni()).containsExactly("demo");
    }

    /** Come all'avvio con SEED_DEMO=true e ADMIN_EMAIL: un admin nel database e il seeder acceso su di lui */
    private Utente accendiIlSeed() {
        Utente admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));
        ReflectionTestUtils.setField(seeder, "abilitato", true);
        ReflectionTestUtils.setField(seeder, "adminEmail", admin.getEmail());
        return admin;
    }

    /** I nomi dei seed che hanno lasciato il segno nel database */
    private List<String> segni() {
        return jdbc.queryForList("select nome from seed_eseguiti", String.class);
    }

    /** Confronto sul JSON e non sui record: un blocco assente è null in uno e NullNode nell'altro, ma in JSON è lo stesso */
    private JsonNode comeJson(TappaDTO tappa) {
        return mapper.valueToTree(tappa);
    }
}
