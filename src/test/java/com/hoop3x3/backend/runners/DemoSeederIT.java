package com.hoop3x3.backend.runners;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.CopiaPubblicaDTO;
import com.hoop3x3.backend.dto.VoceArchivioDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Lega;
import com.hoop3x3.backend.entities.Tappa;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import com.hoop3x3.backend.repositories.LegaRepository;
import com.hoop3x3.backend.repositories.SeedEseguitoRepository;
import com.hoop3x3.backend.repositories.TappaRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.LegaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Il seed demo con il database vero: le 4 tappe del circuito Estathé finiscono in archivio con la forma che il frontend
 * legge dalle API delle tappe, intestate all'admin. Il seeder parte da solo solo all'avvio (SEED_DEMO=true) e i test
 * cominciano con le tabelle vuote: qui lo si accende a mano, dopo aver creato l'admin. Il seed si esegue una sola volta,
 * ricordato dal segno nella tabella seed_eseguiti (vedi SeedEseguito).
 */
@TestDiIntegrazione
class DemoSeederIT {

    // Il bean del contesto è spento, come vuole il profilo di prova (seed.demo=false, nessuna email dell'admin): i test che lo
    // vogliono acceso ne costruiscono uno loro (accendiIlSeed), così il bean condiviso con gli altri test non cambia
    @Autowired DemoSeeder seeder;
    @Autowired SeedEseguitoRepository seedEseguiti;
    @Autowired UtenteRepository utenti;
    @Autowired LegaRepository leghe;
    @Autowired TappaRepository tappe;
    @Autowired AnagrafeGiocatoreRepository giocatori;
    @Autowired AnagrafeSquadraRepository squadre;
    @Autowired JdbcTemplate jdbc;
    @Autowired ArchivioService archivioService;
    @Autowired LegaService legaService;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transazioni;

    @Test
    void leTappeDelSeedSonoInArchivioUgualiAQuelleSalvateEIntestateAllAdmin() throws Exception {
        Utente admin = accendiIlSeed();

        seeder.run();

        String nomeLega = leghe.findAll().getFirst().getNome();
        List<VoceArchivioDTO> elenco = archivioService.tutte();
        assertThat(elenco).hasSize(4).allSatisfy(voce -> {
            assertThat(voce.lega()).isEqualTo(nomeLega);
            assertThat(voce.autore()).isEqualTo("Admin");
            // L'elenco è sintetico: autore e tappa si guardano nella pubblicazione, come fa l'app aprendola
            CopiaPubblicaDTO pubblicata = archivioService.una(voce.tappaId());
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
        // I nomi dei dati demo cambiano (sono diventati di fantasia, TR-4): il segno non deve dipendere da nessuno di loro
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

    // I dati demo sono di fantasia e coerenti (TR-4): nell'archivio ogni giocatore delle squadre delle tappe ha il nome con cui
    // il seed l'ha scritto nell'anagrafe (la stessa persona non ha due nomi), e il referente di ogni squadra è una persona
    @Test
    void iGiocatoriDelleTappeInArchivioHannoIlNomeDellAnagrafe() throws Exception {
        accendiIlSeed();

        seeder.run();

        Set<String> nomiInAnagrafe = giocatori.findAll().stream()
                .map(g -> g.getNome() + " " + g.getCognome()).collect(Collectors.toSet());
        assertThat(nomiInAnagrafe).as("32 giocatori, tutti con un nome diverso").hasSize(32);
        int letti = 0;
        for (VoceArchivioDTO voce : archivioService.tutte()) {
            for (JsonNode squadra : archivioService.una(voce.tappaId()).tappa().squadre()) {
                for (JsonNode giocatore : squadra.path("giocatori")) {
                    assertThat(nomiInAnagrafe).contains(giocatore.path("nome").asString());
                    letti++;
                }
            }
        }
        assertThat(letti).as("giocatori letti nelle tappe in archivio").isEqualTo(128);
        assertThat(squadre.findAll()).allSatisfy(s -> assertThat(s.getReferente()).contains(" "));
    }

    // Il seed è una transazione sola: se si ferma a metà il database resta com'era e il prossimo avvio riprova da capo, senza
    // giocatori e squadre doppi. E non ferma l'avvio: run() non lancia, nei log c'è un avviso. Qui si ferma sulla pubblicazione
    // in archivio, l'ultimo passo: giocatori, squadre e lega, scritti prima, non devono restare
    @Test
    void seIlSeedSiFermaAMeta_ilDatabaseRestaComEraEIlServerParte() {
        Utente admin = accendiIlSeed();
        leghe.save(new Lega("Altra lega", admin));
        ArchivioService archivioRotto = mock(ArchivioService.class);
        doThrow(new IllegalStateException("archivio non disponibile")).when(archivioRotto).pubblica(any(), any());
        seeder = seederAcceso(admin, archivioRotto);

        try (LogCatturato log = new LogCatturato(DemoSeeder.class)) {
            assertThatCode(() -> seeder.run()).as("il seed fallito non ferma l'avvio").doesNotThrowAnyException();
            assertThat(log.righe()).singleElement().asString().startsWith("Seed demo non riuscito");
        }

        assertThat(giocatori.count()).as("nessun giocatore demo").isZero();
        assertThat(squadre.count()).as("nessuna squadra demo").isZero();
        assertThat(tappe.count()).as("nessuna tappa demo").isZero();
        assertThat(leghe.count()).as("solo la lega che c'era").isEqualTo(1);
        assertThat(segni()).as("nessun segno: il prossimo avvio riprova").isEmpty();
    }

    // Gli id delle tappe demo sono casuali: una tappa di un altro utente non può avere l'id di una tappa demo e bloccare il seed
    @Test
    void gliIdDelleTappeDemoSonoCasuali_dueSeedDannoIdDiversi() throws Exception {
        accendiIlSeed();
        seeder.run();
        List<UUID> primaVolta = tappe.findAll().stream().map(Tappa::getId).toList();
        jdbc.update("delete from seed_eseguiti");
        leghe.deleteAll();

        seeder.run();

        assertThat(tappe.findAll()).hasSize(4).extracting(Tappa::getId).doesNotContainAnyElementsOf(primaVolta);
    }

    /** Come all'avvio con SEED_DEMO=true e ADMIN_EMAIL: un admin nel database e il seeder acceso su di lui */
    private Utente accendiIlSeed() {
        Utente admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));
        seeder = seederAcceso(admin, archivioService);
        return admin;
    }

    /** Un seeder acceso sull'admin, con l'archivio indicato (quello vero, o uno che fallisce): la transazione è quella vera */
    private DemoSeeder seederAcceso(Utente admin, ArchivioService archivio) {
        return new DemoSeeder(utenti, giocatori, squadre, leghe, seedEseguiti, archivio, mapper, transazioni,
                new SeedProperties(true, new SeedProperties.Admin(admin.getEmail(), "")));
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
