package com.hoop3x3.backend;

import com.hoop3x3.backend.dto.CampettoDTO;
import com.hoop3x3.backend.dto.GiocatoreDTO;
import com.hoop3x3.backend.dto.LegaMetaDTO;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.PatchLegaDTO;
import com.hoop3x3.backend.dto.SquadraDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;
import com.hoop3x3.backend.entities.ArchivioTappa;
import com.hoop3x3.backend.entities.Campetto;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Tappa;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import com.hoop3x3.backend.repositories.CampettoRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.CampettoService;
import com.hoop3x3.backend.services.LegaService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * BE-8, letture pesanti. Le statistiche di Hibernate contano le istruzioni SQL di ogni lettura: devono essere poche e
 * sempre le stesse, non una per riga. Ogni test misura la stessa lettura con poche righe e con più righe e pretende lo
 * stesso numero: un elenco che interroga il database una volta per riga ne fa di più quando le righe crescono.
 */
@TestDiIntegrazione
class LettureEfficientiIT {

    @Autowired EntityManagerFactory emf;
    @Autowired UtenteRepository utenti;
    @Autowired AnagrafeGiocatoreRepository giocatori;
    @Autowired AnagrafeSquadraRepository squadre;
    @Autowired AnagrafeService anagrafeService;
    @Autowired LegaService legaService;
    @Autowired ArchivioService archivioService;
    @Autowired CampettoService campettoService;
    @Autowired CampettoRepository campetti;
    @Autowired JdbcTemplate jdbc;

    private Statistics statistiche;
    private boolean eranoAccese; // com'erano le statistiche prima del test
    private int progressivo; // dà un nome diverso a ogni lega, squadra e autore di prova

    // Le statistiche si accendono qui, solo per la durata del test: da proprietà (hibernate.generate_statistics) tutti i
    // test di integrazione stamperebbero «Session Metrics» a ogni sessione. Il contesto è condiviso con gli altri IT,
    // quindi a fine test si rimettono come erano
    @BeforeEach
    void accendiLeStatistiche() {
        statistiche = emf.unwrap(SessionFactory.class).getStatistics();
        eranoAccese = statistiche.isStatisticsEnabled();
        statistiche.setStatisticsEnabled(true);
    }

    @AfterEach
    void rimettiLeStatistiche() {
        statistiche.setStatisticsEnabled(eranoAccese);
    }

    /* ── Indice delle leghe: una query che conta le tappe, senza caricarle (cinque colonne JSONB l'una) ── */

    @Test
    void indiceDelleLegheSiLeggeConUnaQuery_ilNumeroNonCresceConLeLeghe() {
        Utente mario = utente("Mario");
        aggiungiLeghe(mario, 1);
        long conUna = misura(() -> legaService.indice(mario)).query();

        aggiungiLeghe(mario, 4);
        var conCinque = misura(() -> legaService.indice(mario));

        assertThat(conCinque.risultato()).hasSize(5);
        assertThat(conCinque.query()).as("query dell'indice con 5 leghe").isEqualTo(conUna);
        assertThat(conCinque.query()).as("query dell'indice").isEqualTo(1);
        assertThat(caricate(Tappa.class)).as("tappe caricate per contarle").isZero();
    }

    @Test
    void indiceHaLeLeghePropriePiuRecentiPerPrime_ognunaConIlNumeroDelleSueTappe() {
        Utente mario = utente("Mario");
        Utente luigi = utente("Luigi");
        LegaMetaDTO senzaTappe = legaService.crea(mario, new NuovaLegaDTO("Senza tappe", null)); // una lega vuota c'è, con 0 tappe
        LegaMetaDTO conTreTappe = legaService.crea(mario, new NuovaLegaDTO("Con tre tappe", List.of(tappa(), tappa(), tappa())));
        legaService.crea(luigi, new NuovaLegaDTO("Di Luigi", List.of(tappa())));

        List<LegaMetaDTO> indice = legaService.indice(mario);

        assertThat(indice).extracting(LegaMetaDTO::id).containsExactly(conTreTappe.id(), senzaTappe.id());
        assertThat(indice).extracting(LegaMetaDTO::nome).containsExactly("Con tre tappe", "Senza tappe");
        assertThat(indice).extracting(LegaMetaDTO::nTappe).containsExactly(3, 0);
        // ts in millisecondi epoch dall'ora UTC salvata nel database (Tempo): se la conversione sbagliasse fuso, sarebbe lontano di ore
        assertThat(indice).allSatisfy(voce -> assertThat(voce.ts()).isCloseTo(System.currentTimeMillis(), within(60_000L)));
    }

    // Dopo la creazione e la rinomina il numero di tappe si conta con una query: caricarle per contarle (le leggerebbe tutte,
    // con i cinque blocchi JSONB di ciascuna) è ciò che faceva la rinomina
    @Test
    void creareERinominareUnaLegaRispondonoConIlNumeroDiTappe_senzaCaricarle() {
        Utente mario = utente("Mario");

        LegaMetaDTO creata = legaService.crea(mario, new NuovaLegaDTO("Circuito", List.of(tappa(), tappa(), tappa())));
        var rinominata = misura(() -> legaService.rinomina(mario, creata.id(), new PatchLegaDTO("Circuito 2026")));

        assertThat(creata.nTappe()).isEqualTo(3);
        assertThat(rinominata.risultato().nome()).isEqualTo("Circuito 2026");
        assertThat(rinominata.risultato().nTappe()).isEqualTo(3);
        assertThat(caricate(Tappa.class)).as("tappe caricate dalla rinomina").isZero();
    }

    /* ── Dettaglio di una lega e scritture sulle tappe: ognuna ha cinque colonne JSONB, quindi si caricano solo quelle che servono ── */

    // Il dettaglio legge la lega e le sue tappe, e l'autore non si carica (il proprietario serve solo per l'id): due query, sia con
    // una tappa sia con cinque
    @Test
    void ilDettaglioDiUnaLegaSiLeggeConDueQuery_ilNumeroNonCresceConLeTappe() {
        Utente mario = utente("Mario");
        UUID conUna = legaService.crea(mario, new NuovaLegaDTO("Con una tappa", List.of(tappa()))).id();
        UUID conCinque = legaService.crea(mario, new NuovaLegaDTO("Con cinque tappe",
                List.of(tappa(), tappa(), tappa(), tappa(), tappa()))).id();

        long queryConUnaTappa = misura(() -> legaService.dettaglio(mario, conUna)).query();
        var cinque = misura(() -> legaService.dettaglio(mario, conCinque));

        assertThat(cinque.risultato().tappe()).hasSize(5);
        assertAll(
                () -> assertThat(cinque.query()).as("query del dettaglio con 5 tappe").isEqualTo(queryConUnaTappa),
                () -> assertThat(cinque.query()).as("query del dettaglio di una lega").isEqualTo(2));
    }

    // La tappa nuova si aggiunge senza leggere quelle che la lega ha già: aggiungerla a una lega di cinque tappe non ne carica
    // nessuna e costa le stesse query che aggiungerla a una lega di una
    @Test
    void aggiungereUnaTappaNonCaricaLeTappeDellaLega_leQueryNonDipendonoDalLoroNumero() {
        Utente mario = utente("Mario");
        UUID conUna = legaService.crea(mario, new NuovaLegaDTO("Con una tappa", List.of(tappa()))).id();
        UUID conCinque = legaService.crea(mario, new NuovaLegaDTO("Con cinque tappe",
                List.of(tappa(), tappa(), tappa(), tappa(), tappa()))).id();

        long queryConUnaTappa = query(() -> legaService.aggiungiTappa(mario, conUna, tappa()));
        long queryConCinqueTappe = query(() -> legaService.aggiungiTappa(mario, conCinque, tappa()));

        assertAll(
                () -> assertThat(queryConCinqueTappe).as("query con 5 tappe già nella lega").isEqualTo(queryConUnaTappa),
                () -> assertThat(caricate(Tappa.class)).as("tappe caricate").isZero());
    }

    // Il salvataggio di una tappa legge quella tappa e basta, anche se la lega ne ha altre: quattro istruzioni, la lettura della
    // tappa e della sua lega e i due UPDATE (la tappa, e la data di modifica della lega)
    @Test
    void salvareUnaTappaCaricaSoloQuellaTappa() {
        Utente mario = utente("Mario");
        TappaDTO prima = tappa();
        legaService.crea(mario, new NuovaLegaDTO("Con cinque tappe", List.of(prima, tappa(), tappa(), tappa(), tappa())));

        long queryDelSalvataggio = query(() -> legaService.aggiornaTappa(mario, prima.id(),
                TappaDiProva.da(prima).nome("Cambiata").versione(0L).build()));

        assertAll(
                () -> assertThat(caricate(Tappa.class)).as("tappe caricate dal salvataggio").isEqualTo(1),
                () -> assertThat(queryDelSalvataggio).as("query del salvataggio di una tappa").isEqualTo(4));
    }

    /* ── Anagrafe: squadre e giocatori in una query, con roster e autore insieme alle righe ── */

    @Test
    void leSquadreSiLeggonoConUnaQuery_ilNumeroNonCresceConLeSquadre() {
        aggiungiSquadre(1);
        long conUna = misura(anagrafeService::tutteSquadre).query();

        aggiungiSquadre(4); // ognuna con un autore diverso e tre giocatori nel roster
        var conCinque = misura(anagrafeService::tutteSquadre);

        assertThat(conCinque.risultato()).hasSize(5);
        assertThat(conCinque.query()).as("query dell'elenco con 5 squadre").isEqualTo(conUna);
        assertThat(conCinque.query()).as("query dell'elenco delle squadre").isEqualTo(1);
    }

    // Caricare il roster insieme alle squadre non deve cambiare ciò che l'API restituisce: l'ordine dei giocatori è quello
    // delle posizioni salvate (@OrderColumn), e una squadra senza giocatori resta nell'elenco. Se le righe del ponte le
    // scrivesse Hibernate sarebbero già nell'ordine delle posizioni, e uscirebbe giusto anche l'ordine di una «bag», che
    // viene dalle righe SQL: per questo le scrive il test con JDBC, nell'ordine 2, 0, 1
    @Test
    void leSquadreHannoAutoreERosterNellOrdineDellePosizioni_ancheSenzaGiocatori() {
        Utente mario = utente("Mario");
        AnagrafeGiocatore rossi = giocatore(mario, "Rossi");
        AnagrafeGiocatore bianchi = giocatore(mario, "Bianchi");
        AnagrafeGiocatore verdi = giocatore(mario, "Verdi");
        AnagrafeSquadra lupi = squadra(mario, "Lupi");
        // Posizioni: 0 verdi, 1 rossi, 2 bianchi (né l'ordine di creazione né quello alfabetico), scritte nell'ordine 2, 0, 1
        rigaDelRoster(lupi, 2, bianchi);
        rigaDelRoster(lupi, 0, verdi);
        rigaDelRoster(lupi, 1, rossi);
        AnagrafeSquadra senzaGiocatori = squadra(mario, "Senza giocatori");

        List<SquadraDTO> lette = anagrafeService.tutteSquadre();

        assertThat(lette).extracting(SquadraDTO::id).containsExactly(senzaGiocatori.getId(), lupi.getId()); // la più recente prima
        assertThat(lette.get(0).roster()).isEmpty();
        assertThat(lette.get(1).roster()).containsExactly(verdi.getId(), rossi.getId(), bianchi.getId());
        assertThat(lette).allSatisfy(squadra -> {
            assertThat(squadra.autore()).isEqualTo("Mario");
            assertThat(squadra.autoreId()).isEqualTo(mario.getId());
        });
    }

    @Test
    void iGiocatoriSiLeggonoConUnaQuery_ilNumeroNonCresceConIGiocatori() {
        aggiungiGiocatori(1);
        long conUno = misura(anagrafeService::tuttiGiocatori).query();

        aggiungiGiocatori(4); // ognuno con un autore diverso
        var conCinque = misura(anagrafeService::tuttiGiocatori);

        assertThat(conCinque.risultato()).hasSize(5);
        assertThat(conCinque.risultato()).extracting(GiocatoreDTO::autore).doesNotHaveDuplicates();
        assertThat(conCinque.query()).as("query dell'elenco con 5 giocatori").isEqualTo(conUno);
        assertThat(conCinque.query()).as("query dell'elenco dei giocatori").isEqualTo(1);
    }

    // Le forme pubbliche usano la stessa lettura delle complete: una query sola per elenco, anche con più righe e più autori
    @Test
    void leFormePubblicheSiLeggonoConUnaQuery() {
        aggiungiSquadre(3); // ognuna con un autore diverso e tre giocatori nel roster

        var squadrePubbliche = misura(anagrafeService::tutteSquadrePubbliche);
        var giocatoriPubblici = misura(anagrafeService::tuttiGiocatoriPubblici);

        assertThat(squadrePubbliche.risultato()).hasSize(3).allSatisfy(squadra -> assertThat(squadra.autore()).isEmpty());
        assertThat(giocatoriPubblici.risultato()).hasSize(9).allSatisfy(giocatore -> assertThat(giocatore.autore()).isEmpty());
        assertAll(
                () -> assertThat(squadrePubbliche.query()).as("query dell'elenco pubblico delle squadre").isEqualTo(1),
                () -> assertThat(giocatoriPubblici.query()).as("query dell'elenco pubblico dei giocatori").isEqualTo(1));
    }

    /* ── eliminaGiocatore: carica solo le squadre che lo contengono, non tutte ── */

    // Il giocatore esce dai roster che lo contengono. Gli altri restano dov'erano e nello stesso ordine, senza buchi nelle
    // posizioni (@OrderColumn), e le squadre che non lo contengono non cambiano. Se il roster si caricasse filtrato (solo il
    // giocatore cercato), salvarlo senza di lui cancellerebbe tutti gli altri
    @Test
    void eliminareUnGiocatoreLoTogliDaiRoster_gliAltriRestanoNellOrdineSenzaBuchi() {
        Utente mario = utente("Mario");
        AnagrafeGiocatore a = giocatore(mario, "A");
        AnagrafeGiocatore b = giocatore(mario, "B");
        AnagrafeGiocatore c = giocatore(mario, "C");
        AnagrafeSquadra conTutti = squadra(mario, "Con tutti", a, b, c);
        AnagrafeSquadra conBeC = squadra(mario, "Con B e C", b, c);
        AnagrafeSquadra senzaB = squadra(mario, "Senza B", c, a);

        anagrafeService.eliminaGiocatore(mario, b.getId());

        assertThat(giocatori.existsById(b.getId())).isFalse();
        assertThat(rosterSalvato(conTutti)).containsExactly(a.getId(), c.getId());
        assertThat(posizioniSalvate(conTutti)).containsExactly(0, 1);
        assertThat(rosterSalvato(conBeC)).containsExactly(c.getId());
        assertThat(posizioniSalvate(conBeC)).containsExactly(0);
        assertThat(rosterSalvato(senzaB)).containsExactly(c.getId(), a.getId());
    }

    @Test
    void eliminareUnGiocatoreCaricaSoloLeSquadreCheLoContengono_leQueryNonDipendonoDalleAltre() {
        Utente mario = utente("Mario");
        AnagrafeGiocatore altro = giocatore(mario, "Altro");
        // Prima eliminazione: il giocatore è in 2 squadre, una come primo e una come ultimo, e c'è 1 squadra senza di lui
        AnagrafeGiocatore primo = giocatore(mario, "Primo");
        squadra(mario, "Con il primo 1", primo, altro);
        squadra(mario, "Con il primo 2", altro, primo);
        squadra(mario, "Senza 1", altro);
        long conUnaSenzaDiLui = query(() -> anagrafeService.eliminaGiocatore(mario, primo.getId()));

        // Seconda: è nelle stesse due posizioni di due squadre, ma ora quelle senza di lui sono 7
        AnagrafeGiocatore secondo = giocatore(mario, "Secondo");
        squadra(mario, "Con il secondo 1", secondo, altro);
        squadra(mario, "Con il secondo 2", altro, secondo);
        for (int i = 2; i <= 5; i++) {
            squadra(mario, "Senza " + i, altro);
        }
        long conSetteSenzaDiLui = query(() -> anagrafeService.eliminaGiocatore(mario, secondo.getId()));

        // Le due cose sono indipendenti: se falliscono entrambe si vedono entrambe
        assertAll(
                () -> assertThat(caricate(AnagrafeSquadra.class)).as("squadre caricate").isEqualTo(2),
                () -> assertThat(conSetteSenzaDiLui).as("query con 7 squadre senza il giocatore").isEqualTo(conUnaSenzaDiLui));
    }

    /* ── Elenco dell'archivio: una query che estrae i campi dal JSONB, senza caricare le pubblicazioni ── */

    @Test
    void elencoDellArchivioSiLeggeConUnaQuery_ilNumeroNonCresceConLePubblicazioni() {
        pubblicaTappe(1);
        long conUna = misura(archivioService::tutte).query();

        pubblicaTappe(4); // ognuna di un autore diverso
        var conCinque = misura(archivioService::tutte);

        assertThat(conCinque.risultato()).hasSize(5);
        // Le tre cose sono indipendenti: se falliscono insieme si vedono insieme
        assertAll(
                () -> assertThat(conCinque.query()).as("query dell'elenco con 5 pubblicazioni").isEqualTo(conUna),
                () -> assertThat(conCinque.query()).as("query dell'elenco dell'archivio").isEqualTo(1),
                () -> assertThat(caricate(ArchivioTappa.class)).as("pubblicazioni caricate").isZero());
    }

    /* ── Campetti: 5.000 campetti sparsi in Italia, le ricerche fanno una query ciascuna (il tempo non si asserisce: in CI è fragile) ── */

    // La ricerca per raggio chiede al database un riquadro di coordinate (indice su lat, lng) e calcola la distanza in Java:
    // una query, anche con 5.000 righe
    @Test
    void laRicercaPerRaggioSu5000CampettiFaUnaQuery_dalPiuVicino() {
        campettiInItalia(5000);
        Campetto vicino = campetto("Campo vicino", "Roma", 41.905, 12.50);
        Campetto medio = campetto("Campo medio", "Roma", 41.95, 12.55);
        Campetto lontano = campetto("Campo lontano", "Roma", 42.05, 12.40);

        var misura = misura(() -> campettoService.cercaPerRaggio(ROMA_LAT, ROMA_LNG, 20));

        List<CampettoDTO> trovati = misura.risultato();
        assertThat(trovati).extracting(CampettoDTO::id).containsSubsequence(vicino.getId(), medio.getId(), lontano.getId());
        List<Double> distanze = trovati.stream().map(c -> distanzaDaRoma(c.lat(), c.lng())).toList();
        assertThat(distanze).isSorted().allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(20));
        assertThat(misura.query()).as("query della ricerca per raggio").isEqualTo(1);
    }

    // La ricerca per testo: una query con il limite di 200 dentro
    @Test
    void laRicercaPerTestoSu5000CampettiFaUnaQuery_alMassimo200Righe() {
        campettiInItalia(5000); // 250 a «Roma»: più del limite

        var misura = misura(() -> campettoService.cercaPerTesto("roma", null, null));

        assertThat(misura.risultato()).hasSize(CampettoService.MASSIMO_RIGHE)
                .allSatisfy(c -> assertThat(c.citta()).isEqualTo("Roma"));
        assertThat(misura.query()).as("query della ricerca per testo").isEqualTo(1);
    }

    // Con un punto, i 200 di un testo che ne trova 250 devono essere i 200 più vicini a quel punto, dal più vicino: se il database
    // tagliasse a 200 per città e nome e si riordinasse dopo, i più vicini potrebbero mancare. Le 250 righe «Roma» stanno in tutta
    // Italia (le città vanno a turno): l'ordine del database deve essere quello sulla sfera, un'approssimazione piana sbaglia il bordo
    @Test
    void laRicercaPerTestoConUnPunto_rispondeI200PiuViciniTraTuttiQuelliTrovati() {
        List<Object[]> righe = campettiInItalia(5000);
        List<String> aRomaDalPiuVicino = righe.stream()
                .filter(r -> "Roma".equals(r[2]))
                .sorted(Comparator.comparingDouble(r -> distanzaDaRoma((double) r[3], (double) r[4])))
                .map(r -> (String) r[1])
                .toList();
        assertThat(aRomaDalPiuVicino).as("più del limite").hasSize(250);

        var misura = misura(() -> campettoService.cercaPerTesto("roma", ROMA_LAT, ROMA_LNG));

        assertThat(misura.risultato()).extracting(CampettoDTO::nome)
                .containsExactlyElementsOf(aRomaDalPiuVicino.subList(0, CampettoService.MASSIMO_RIGHE));
        assertThat(misura.query()).as("query della ricerca per testo con un punto").isEqualTo(1);
    }

    /* ── Dati di prova ── */

    private static final double ROMA_LAT = 41.9028;
    private static final double ROMA_LNG = 12.4964;
    /** Venti città a turno: con 5.000 campetti ognuna ne ha 250, più del limite di 200 di una ricerca */
    private static final List<String> CITTA = List.of("Roma", "Milano", "Napoli", "Torino", "Palermo", "Genova", "Bologna",
            "Firenze", "Bari", "Catania", "Venezia", "Verona", "Messina", "Padova", "Trieste", "Brescia", "Parma", "Prato",
            "Modena", "Reggio Calabria");

    /** Un campetto di prova con quelle coordinate, senza autore */
    private Campetto campetto(String nome, String citta, double lat, double lng) {
        Campetto c = new Campetto();
        c.setNome(nome);
        c.setCitta(citta);
        c.setLat(lat);
        c.setLng(lng);
        c.setSuperficie("Asfalto");
        c.setCanestri((short) 2);
        c.setStato("buono");
        return campetti.save(c);
    }

    /**
     * `quanti` campetti con coordinate casuali (seme fisso: ogni esecuzione ha gli stessi) nel riquadro dell'Italia, con le
     * città a turno; scritti con un batch JDBC, perché 5.000 save di Hibernate durerebbero più del test. Restituisce le
     * righe scritte, per i test che devono calcolare il risultato atteso
     */
    private List<Object[]> campettiInItalia(int quanti) {
        Random caso = new Random(5000);
        List<Object[]> righe = new ArrayList<>();
        for (int i = 0; i < quanti; i++) {
            double lat = 36.6 + caso.nextDouble() * 10.5;
            double lng = 6.6 + caso.nextDouble() * 11.9;
            righe.add(new Object[] {UUID.randomUUID(), "Campo " + i, CITTA.get(i % CITTA.size()), lat, lng});
        }
        jdbc.batchUpdate("insert into campetti (id, nome, indirizzo, citta, lat, lng, tipo, superficie, canestri, stato, note, "
                + "versione, creato_il, modificato_il) values (?, ?, '', ?, ?, ?, 'campetto', 'Asfalto', 2, 'buono', '', 0, "
                + "now(), now())", righe);
        return righe;
    }

    private static double distanzaDaRoma(double lat, double lng) {
        return CampettoService.distanzaKm(ROMA_LAT, ROMA_LNG, lat, lng);
    }

    /** Aggiunge `quante` leghe a `proprietario`, ognuna con due tappe */
    private void aggiungiLeghe(Utente proprietario, int quante) {
        for (int i = 0; i < quante; i++) {
            legaService.crea(proprietario, new NuovaLegaDTO("Lega " + (++progressivo), List.of(tappa(), tappa())));
        }
    }

    /** Pubblica `quante` tappe, ognuna conclusa nella lega di un autore nuovo */
    private void pubblicaTappe(int quante) {
        for (int i = 0; i < quante; i++) {
            Utente autore = utente("Autore " + (++progressivo));
            TappaDTO tappa = tappa();
            legaService.crea(autore, new NuovaLegaDTO("Lega " + progressivo, List.of(tappa)));
            archivioService.pubblica(autore, tappa.id());
        }
    }

    /** Aggiunge `quante` squadre, ognuna di un autore nuovo con tre giocatori suoi nel roster */
    private void aggiungiSquadre(int quante) {
        for (int i = 0; i < quante; i++) {
            Utente autore = utente("Autore " + (++progressivo));
            squadra(autore, "Squadra " + progressivo, giocatore(autore, "Primo"), giocatore(autore, "Secondo"),
                    giocatore(autore, "Terzo"));
        }
    }

    /** Aggiunge `quanti` giocatori, ognuno di un autore nuovo */
    private void aggiungiGiocatori(int quanti) {
        for (int i = 0; i < quanti; i++) {
            giocatore(utente("Autore " + (++progressivo)), "Cognome");
        }
    }

    private AnagrafeGiocatore giocatore(Utente autore, String cognome) {
        AnagrafeGiocatore g = new AnagrafeGiocatore();
        g.setNome("Nome");
        g.setCognome(cognome);
        g.setAutore(autore);
        return giocatori.save(g);
    }

    /** Una squadra con il roster nell'ordine indicato */
    private AnagrafeSquadra squadra(Utente autore, String nome, AnagrafeGiocatore... roster) {
        AnagrafeSquadra s = new AnagrafeSquadra();
        s.setNome(nome);
        s.setAutore(autore);
        s.getRoster().addAll(List.of(roster));
        return squadre.save(s);
    }

    /** Scrive con JDBC una riga della tabella ponte del roster, alla posizione indicata */
    private void rigaDelRoster(AnagrafeSquadra squadra, int posizione, AnagrafeGiocatore giocatore) {
        jdbc.update("insert into anagrafe_squadre_roster (squadra_id, giocatore_id, posizione) values (?, ?, ?)",
                squadra.getId(), giocatore.getId(), posizione);
    }

    /** Gli id del roster come sono nella tabella ponte, per posizione */
    private List<UUID> rosterSalvato(AnagrafeSquadra squadra) {
        return jdbc.queryForList("select giocatore_id from anagrafe_squadre_roster where squadra_id = ? order by posizione",
                UUID.class, squadra.getId());
    }

    private List<Integer> posizioniSalvate(AnagrafeSquadra squadra) {
        return jdbc.queryForList("select posizione from anagrafe_squadre_roster where squadra_id = ? order by posizione",
                Integer.class, squadra.getId());
    }

    /** Un utente con questo nome (l'email è sempre diversa) */
    private Utente utente(String nome) {
        return utenti.save(new Utente(UUID.randomUUID() + "@test.it", "hash", nome, Ruolo.USER));
    }

    /** Una tappa qualsiasi, con un id nuovo */
    private TappaDTO tappa() {
        return TappaDiProva.tappa().squadre("[{\"id\":\"s1\"},{\"id\":\"s2\"}]").conclusa(true).build();
    }

    /* ── Misure ── */

    /** Il risultato di un'azione e le istruzioni SQL che Hibernate ha eseguito per ottenerlo */
    private record Misura<T>(T risultato, long query) {}

    private <T> Misura<T> misura(Supplier<T> azione) {
        statistiche.clear();
        T risultato = azione.get();
        return new Misura<>(risultato, statistiche.getPrepareStatementCount());
    }

    /** Le istruzioni SQL che Hibernate esegue per un'azione che non restituisce nulla */
    private long query(Runnable azione) {
        return misura(() -> {
            azione.run();
            return null;
        }).query();
    }

    /** Quante istanze di `entity` Hibernate ha caricato dal database dall'ultima misura */
    private long caricate(Class<?> entity) {
        return statistiche.getEntityStatistics(entity.getName()).getLoadCount();
    }
}
