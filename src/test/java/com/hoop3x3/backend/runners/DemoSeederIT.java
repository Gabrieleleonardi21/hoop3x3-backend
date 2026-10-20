package com.hoop3x3.backend.runners;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.CampettoDTO;
import com.hoop3x3.backend.dto.CopiaPubblicaDTO;
import com.hoop3x3.backend.dto.VoceArchivioDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Campetto;
import com.hoop3x3.backend.entities.Lega;
import com.hoop3x3.backend.entities.Tappa;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import com.hoop3x3.backend.repositories.CampettoRepository;
import com.hoop3x3.backend.repositories.LegaRepository;
import com.hoop3x3.backend.repositories.SeedEseguitoRepository;
import com.hoop3x3.backend.repositories.TappaRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.CampettoService;
import com.hoop3x3.backend.services.LegaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
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
    @Autowired CampettoRepository campetti;
    @Autowired CampettoService campettoService;
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

        assertThat(segni()).containsExactlyInAnyOrder("demo", "campetti", "campetti-roma");
        assertThat(jdbc.queryForObject("select count(*) from seed_eseguiti where eseguito_il is not null", Integer.class))
                .as("i segni hanno la data").isEqualTo(3);
    }

    // Con SEED_CAMPETTI da solo (SEED_DEMO spento), sul database vero: nove campetti intestati all'admin e né il circuito, né la
    // lega, né le persone. Il seed non riparte al riavvio
    @Test
    void soloSeedCampetti_sulDatabaseVero_noveCampettiSenzaCircuito() {
        Utente admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));
        DemoSeeder soloCampetti = new DemoSeeder(utenti, giocatori, squadre, leghe, campetti, seedEseguiti, archivioService, mapper,
                transazioni, new SeedProperties(false, true, new SeedProperties.Admin(admin.getEmail(), "")));

        soloCampetti.run();
        soloCampetti.run(); // il riavvio

        assertThat(campetti.count()).isEqualTo(9);
        assertThat(jdbc.queryForObject("select count(*) from campetti where autore_id = ?", Integer.class, admin.getId()))
                .isEqualTo(9);
        assertThat(leghe.count()).as("nessuna lega demo").isZero();
        assertThat(giocatori.count()).as("nessun giocatore di fantasia").isZero();
        assertThat(squadre.count()).as("nessuna squadra di fantasia").isZero();
        assertThat(segni()).containsExactlyInAnyOrder("campetti", "campetti-roma");
    }

    /* ── I campetti di Torino ── */

    // I sei campetti del file entrano intestati all'admin, si trovano con la ricerca per raggio dal centro di Torino (dal più
    // vicino) e, eliminati dall'app, non rinascono al riavvio: lo ricorda il segno «campetti»
    @Test
    void iSeiCampettiDiTorinoSonoDellAdmin_siTrovanoPerRaggio_eNonRinasconoDopoLEliminazione() {
        Utente admin = accendiIlSeed();

        seeder.run();

        List<CampettoDTO> vicini = campettoService.cercaPerRaggio(45.07, 7.68, 20);
        assertThat(vicini).hasSize(6).allSatisfy(c -> {
            assertThat(c.autore()).isEqualTo("Admin");
            assertThat(c.autoreId()).isEqualTo(admin.getId());
            assertThat(c.tipo()).isEqualTo("campetto");
            assertThat(c.citta()).isEqualTo("Torino");
            assertThat(c.note()).isEmpty();
            // Il seed non passa dalla validazione del DTO: un refuso nel JSON farebbe respingere l'intero elenco allo schema
            // zod del frontend, quindi i valori ammessi si controllano qui
            assertThat(c.superficie()).isIn("Asfalto", "Cemento", "Sintetico", "Altro");
            assertThat(c.stato()).isIn("buono", "discreto", "da sistemare");
            assertThat(c.canestri()).isBetween(1, 8);
        });
        assertThat(vicini.getFirst().nome()).as("il più vicino al centro").isEqualTo("Giardini Reali — Playground");
        assertThat(vicini).extracting(CampettoDTO::nome).containsExactlyInAnyOrder("Parco Ruffini — Campo 2",
                "Giardini Reali — Playground", "Parco Dora — Le Arcate", "Piazza d'Armi — Spazio 3x3", "Campo Vanchiglia",
                "Parco Colletta — Campo A");
        assertThat(campettoService.cercaPerTesto("dora", null, null)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from campetti where fonte is not null", Integer.class)).isZero();

        campetti.deleteAll();
        seeder.run(); // il riavvio

        assertThat(campetti.count()).isZero();
        assertThat(segni()).contains("campetti");
    }

    // Un database seminato con i sei campetti di Torino (e il loro segno «campetti») ma senza quelli di Roma riceve solo questi
    // tre, al primo avvio: nove campetti, nessuno raddoppiato, quelli di Torino con gli stessi id. Il segno «campetti-roma» è
    // di questo lotto e il seed dei campetti non riparte in blocco, così un campetto eliminato dall'app non rinasce
    @Test
    void databaseConISeiDiTorino_riceveSoloITreDiRomaSenzaDoppioni() {
        accendiIlSeed();
        seeder.run();
        List<UUID> torino = jdbc.queryForList("select id from campetti where citta = 'Torino'", UUID.class);
        jdbc.update("delete from campetti where citta = 'Roma'");
        jdbc.update("delete from seed_eseguiti where nome = 'campetti-roma'");
        long giocatoriDemo = giocatori.count();

        seeder.run();

        assertThat(campetti.count()).as("nove campetti").isEqualTo(9);
        assertThat(jdbc.queryForObject("select count(*) from (select distinct nome, citta from campetti) t", Integer.class))
                .as("nessun campetto doppio").isEqualTo(9);
        assertThat(jdbc.queryForList("select id from campetti where citta = 'Torino'", UUID.class))
                .as("i sei di Torino non sono stati toccati").containsExactlyInAnyOrderElementsOf(torino);
        assertThat(giocatori.count()).as("il seed demo non riparte").isEqualTo(giocatoriDemo);
        assertThat(segni()).containsExactlyInAnyOrder("demo", "campetti", "campetti-roma");
    }

    // I tre campetti di Roma entrano intestati all'admin e si trovano dal centro di Roma; da Torino sono troppo lontani per
    // comparire nella ricerca di Torino
    @Test
    void iTreCampettiDiRoma_siTrovanoDalCentroDiRoma() {
        Utente admin = accendiIlSeed();

        seeder.run();

        List<CampettoDTO> romani = campettoService.cercaPerRaggio(41.9028, 12.4964, 20);
        assertThat(romani).extracting(CampettoDTO::nome).containsExactlyInAnyOrder("Quadrato",
                "Cinodromo — campo da basket", "Playground Parco delle Energie");
        assertThat(romani).allSatisfy(c -> {
            assertThat(c.citta()).isEqualTo("Roma");
            assertThat(c.autoreId()).isEqualTo(admin.getId());
        });
        assertThat(campettoService.cercaPerRaggio(45.07, 7.68, 20)).extracting(CampettoDTO::citta).doesNotContain("Roma");
    }

    // I nove campetti del seed (sei di Torino, tre di Roma) hanno i valori ammessi dal DTO e dallo schema zod del frontend. Il
    // seed non passa dalla validazione: un refuso nel JSON farebbe respingere l'intero elenco, quindi qui si controlla
    @Test
    void iNoveCampettiDelSeedHannoIValoriAmmessi() {
        Utente admin = accendiIlSeed();

        seeder.run();

        List<Campetto> tutti = campetti.findAll();
        assertThat(tutti).hasSize(9).allSatisfy(c -> {
            assertThat(c.getSuperficie()).isIn("Asfalto", "Cemento", "Sintetico", "Altro");
            assertThat(c.getStato()).isIn("buono", "discreto", "da sistemare");
            assertThat(c.getCanestri()).isBetween((short) 1, (short) 8);
            assertThat(c.getTipo()).isEqualTo("campetto");
            assertThat(c.getFonte()).isNull();
        });
        assertThat(jdbc.queryForObject("select count(*) from campetti where autore_id = ?", Integer.class, admin.getId()))
                .as("tutti intestati all'admin").isEqualTo(9);
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
        assertThat(campetti.count()).as("i campetti non raddoppiano").isEqualTo(9);
        assertThat(segni()).containsExactlyInAnyOrder("demo", "campetti", "campetti-roma");
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

    // Ogni giocatore delle squadre delle tappe in archivio porta il regId della sua scheda in anagrafe: il frontend (fase 11a) lo
    // riconosce per id e non deve ripiegare sul nome. Nessuno senza regId, nessun regId che non esiste nell'anagrafe
    @Test
    void iGiocatoriDelleTappeInArchivioHannoIlRegIdDellaLoroScheda() throws Exception {
        accendiIlSeed();

        seeder.run();

        Map<String, String> idPerNome = giocatori.findAll().stream()
                .collect(Collectors.toMap(g -> g.getNome() + " " + g.getCognome(), g -> g.getId().toString()));
        Set<String> idInAnagrafe = Set.copyOf(idPerNome.values());
        int letti = 0;
        for (VoceArchivioDTO voce : archivioService.tutte()) {
            for (JsonNode squadra : archivioService.una(voce.tappaId()).tappa().squadre()) {
                for (JsonNode giocatore : squadra.path("giocatori")) {
                    String regId = giocatore.path("regId").asString();
                    assertThat(regId).as("regId di %s", giocatore.path("nome").asString())
                            .isEqualTo(idPerNome.get(giocatore.path("nome").asString()));
                    assertThat(idInAnagrafe).contains(regId);
                    letti++;
                }
            }
        }
        assertThat(letti).as("giocatori con regId nelle tappe in archivio").isEqualTo(128);
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
        assertThat(campetti.count()).as("nessun campetto: i due seed stanno nella stessa transazione").isZero();
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
        return new DemoSeeder(utenti, giocatori, squadre, leghe, campetti, seedEseguiti, archivio, mapper, transazioni,
                new SeedProperties(true, false, new SeedProperties.Admin(admin.getEmail(), "")));
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
