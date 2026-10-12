package com.hoop3x3.backend.runners;

import com.hoop3x3.backend.entities.*;
import com.hoop3x3.backend.repositories.*;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.UtenteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dati di prova del circuito Estathé 3x3 2025 (anagrafe, lega con 4 tappe concluse e archivio),
 * letti da resources/seed/estathe25.json (le persone, cioè giocatori e referenti, sono di fantasia: nome, data di nascita, misure,
 * città e note sono inventati e la nazionalità è rimescolata, cioè gli italiani restano italiani e gli stranieri sono spostati su
 * altri giocatori; restano solo ruolo, squadra e numero. Il file sta nel jar anche con SEED_DEMO=false e non deve contenere dati
 * di persone reali, lo controlla DatiDiProvaTest). Attivo solo con SEED_DEMO=true e con l'admin configurato
 * (i dati vengono intestati a lui). Si esegue una sola volta: alla fine scrive il segno «demo» in seed_eseguiti e al
 * riavvio lo riconosce da lì (perché serve: vedi SeedEseguito). Gli id corti del file ("p01", "s01", "t01") non contano:
 * gli id delle tappe sono casuali, come quelli che genera l'app (erano derivati dal file, quindi prevedibili: un utente
 * poteva creare una tappa con quell'id e il seed non partiva più). Per i database seminati prima del segno il seed è
 * riconosciuto dalla lega demo: una lega dell'admin con il nome del file. L'archivio lo riempie ArchivioService.pubblica, lo
 * stesso metodo che usa l'app.
 * <p>
 * Il seed non ferma mai l'avvio: gira in una transazione sua e, se qualcosa va storto, il database resta com'era e nei log
 * c'è un avviso con la causa. L'app parte senza i dati di prova.
 * <p>
 * Nella stessa transazione, con le stesse condizioni (SEED_DEMO e l'admin) ma con un segno suo («campetti»), entrano i sei
 * campetti di Torino di resources/seed/campetti-torino.json (le coordinate vengono da OpenStreetMap, con la fonte scritta in
 * testa al file): così li riceve anche un database che ha già il seed demo di prima, e anche loro una volta sola.
 */
@Slf4j
@Component
@Order(2) // dopo DataSeeder: serve l'admin già creato
@EnableConfigurationProperties(SeedProperties.class)
public class DemoSeeder implements CommandLineRunner {

    private static final String FILE = "/seed/estathe25.json";
    /**
     * Il nome del segno in seed_eseguiti è quello dell'operazione, non dei dati demo (nome della lega, delle squadre, del
     * file): i dati possono cambiare e il segno deve restare riconoscibile.
     */
    private static final String SEGNO = "demo";
    private static final String FILE_CAMPETTI = "/seed/campetti-torino.json";
    private static final String SEGNO_CAMPETTI = "campetti";

    private final UtenteRepository utenti;
    private final AnagrafeGiocatoreRepository giocatori;
    private final AnagrafeSquadraRepository squadre;
    private final LegaRepository leghe;
    private final CampettoRepository campetti;
    private final SeedEseguitoRepository seedEseguiti;
    private final ArchivioService archivioService;
    private final ObjectMapper mapper;
    private final TransactionTemplate transazione;

    // SEED_DEMO e ADMIN_EMAIL (seed.*)
    private final boolean abilitato;
    private final String adminEmail;

    public DemoSeeder(UtenteRepository utenti, AnagrafeGiocatoreRepository giocatori, AnagrafeSquadraRepository squadre,
                      LegaRepository leghe, CampettoRepository campetti, SeedEseguitoRepository seedEseguiti,
                      ArchivioService archivioService, ObjectMapper mapper, PlatformTransactionManager transazioni,
                      SeedProperties proprieta) {
        this.abilitato = proprieta.demo();
        this.adminEmail = proprieta.admin().email();
        this.utenti = utenti;
        this.giocatori = giocatori;
        this.squadre = squadre;
        this.leghe = leghe;
        this.campetti = campetti;
        this.seedEseguiti = seedEseguiti;
        this.archivioService = archivioService;
        this.mapper = mapper;
        // La transazione la apre il seeder e non @Transactional sul metodo: così run() può prendere l'errore dopo il rollback e
        // trasformarlo in un avviso, invece di farlo arrivare a Spring Boot, che fermerebbe l'avvio
        this.transazione = new TransactionTemplate(transazioni);
    }

    @Override
    public void run(String... args) {
        // Ogni salto dice perché nei log: INFO se è la configurazione normale, avviso se chi ha acceso il seed non otterrà i dati
        if (!abilitato) {
            log.info("Seed demo saltato: SEED_DEMO non è true");
            return;
        }
        if (adminEmail.isBlank()) {
            log.warn("Seed demo saltato: SEED_DEMO è true ma ADMIN_EMAIL è vuota, e i dati di prova si intestano all'admin");
            return;
        }
        try {
            transazione.executeWithoutResult(_ -> semina());
        } catch (Exception e) {
            // Un seed a metà non deve lasciare l'app senza avvio: la transazione ha già annullato tutto, il prossimo avvio riprova
            log.warn("Seed demo non riuscito, il database resta com'era e il server parte senza i dati di prova", e);
        }
    }

    /** I due seed, dentro la transazione: tutto o niente, segni compresi */
    private void semina() {
        Utente admin = utenti.findByEmail(UtenteService.normalizza(adminEmail)).orElse(null);
        if (admin == null) {
            log.warn("Seed demo saltato: admin {} non trovato", adminEmail);
            return;
        }
        // ADMIN_EMAIL di un utente registrato dall'app: DataSeeder non lo promuove, e i dati di prova non vanno intestati a lui
        if (!admin.isAdmin()) {
            log.warn("Seed demo saltato: {} è di un utente con ruolo USER, non dell'admin", adminEmail);
            return;
        }
        seminaDemo(admin);
        seminaCampetti(admin);
    }

    /** Il circuito Estathé: anagrafe, lega con le tappe e archivio, con il segno «demo» */
    private void seminaDemo(Utente admin) {
        if (seedEseguiti.existsById(SEGNO)) {
            log.info("Seed demo saltato: già eseguito");
            return;
        }

        JsonNode dati = leggiIlFile(FILE);
        // Un database seminato prima del segno non ce l'ha, ma ha ancora la lega demo (dell'admin, con il nome del file): il seed è
        // già stato eseguito. Il segno si scrive adesso, per i prossimi avvii
        if (leghe.existsByOwnerAndNome(admin, testo(dati, "lega"))) {
            seedEseguiti.save(new SeedEseguito(SEGNO));
            log.info("Seed demo saltato: già eseguito prima del segno (la lega demo c'è), segno scritto");
            return;
        }

        Map<String, AnagrafeGiocatore> giocatoriPerId = creaGiocatori(admin, dati.path("giocatori"));
        Map<String, AnagrafeSquadra> squadrePerId = creaSquadre(admin, dati.path("squadre"), giocatoriPerId);
        Lega lega = creaLega(admin, dati, squadrePerId);
        pubblicaInArchivio(admin, lega);
        // Per ultimo: se qualcosa va storto la transazione annulla anche il segno, e il prossimo avvio riprova da capo
        seedEseguiti.save(new SeedEseguito(SEGNO));

        log.info("Seed demo completato: {} giocatori, {} squadre, {} tappe → lega \"{}\"",
                giocatoriPerId.size(), squadrePerId.size(), lega.getTappe().size(), lega.getNome());
    }

    /**
     * I campetti di Torino, intestati all'admin, con il segno «campetti»: un segno a parte perché un database che ha già il
     * seed demo deve riceverli lo stesso, e un campetto eliminato dall'app non deve rinascere al riavvio
     */
    private void seminaCampetti(Utente admin) {
        if (seedEseguiti.existsById(SEGNO_CAMPETTI)) {
            log.info("Seed campetti saltato: già eseguito");
            return;
        }
        int quanti = 0;
        for (JsonNode n : leggiIlFile(FILE_CAMPETTI).path("campetti")) {
            Campetto c = new Campetto();
            c.setAutore(admin);
            c.setNome(testo(n, "nome"));
            c.setIndirizzo(testo(n, "indirizzo"));
            c.setCitta(testo(n, "citta"));
            c.setLat(n.path("lat").asDouble());
            c.setLng(n.path("lng").asDouble());
            c.setSuperficie(testo(n, "superficie"));
            c.setCanestri((short) n.path("canestri").asInt(2));
            c.setIlluminato(n.path("illuminato").asBoolean(false));
            c.setCoperto(n.path("coperto").asBoolean(false));
            c.setGratuito(n.path("gratuito").asBoolean(false));
            c.setRetine(n.path("retine").asBoolean(false));
            c.setLinee(n.path("linee").asBoolean(false));
            c.setFontanella(n.path("fontanella").asBoolean(false));
            c.setStato(testo(n, "stato"));
            c.setNote(testo(n, "note"));
            campetti.save(c);
            quanti++;
        }
        seedEseguiti.save(new SeedEseguito(SEGNO_CAMPETTI));
        log.info("Seed campetti completato: {} campetti", quanti);
    }

    private JsonNode leggiIlFile(String file) {
        try (InputStream in = getClass().getResourceAsStream(file)) {
            return mapper.readTree(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("File dei dati di prova non leggibile: " + file, e);
        }
    }

    private Map<String, AnagrafeGiocatore> creaGiocatori(Utente admin, JsonNode lista) {
        Map<String, AnagrafeGiocatore> perId = new HashMap<>();
        for (JsonNode n : lista) {
            AnagrafeGiocatore g = new AnagrafeGiocatore();
            g.setAutore(admin);
            g.setNome(testo(n, "nome"));
            g.setCognome(testo(n, "cognome"));
            g.setSoprannome(testo(n, "soprannome"));
            g.setNascita(testo(n, "nascita"));
            g.setCitta(testo(n, "citta"));
            g.setNazionalita(testo(n, "nazionalita"));
            g.setAltezza(testo(n, "altezza"));
            g.setPeso(testo(n, "peso"));
            g.setRuolo(testo(n, "ruolo"));
            g.setNumero(testo(n, "numero"));
            g.setSquadra(testo(n, "squadra"));
            g.setEsperienza(testo(n, "esperienza"));
            g.setNote(testo(n, "note"));
            perId.put(testo(n, "id"), giocatori.save(g));
        }
        return perId;
    }

    private Map<String, AnagrafeSquadra> creaSquadre(Utente admin, JsonNode lista, Map<String, AnagrafeGiocatore> giocatoriPerId) {
        Map<String, AnagrafeSquadra> perId = new HashMap<>();
        for (JsonNode n : lista) {
            AnagrafeSquadra s = new AnagrafeSquadra();
            s.setAutore(admin);
            s.setNome(testo(n, "nome"));
            s.setCitta(testo(n, "citta"));
            s.setAnno(testo(n, "anno"));
            s.setRank(testo(n, "rank"));
            s.setReferente(testo(n, "referente"));
            s.setLogo(testo(n, "logo"));
            s.setWebsite(testo(n, "website"));
            s.setInstagram(testo(n, "instagram"));
            s.setNote(testo(n, "note"));
            // Il roster referenzia i giocatori con l'id corto del file
            for (JsonNode pid : n.path("roster")) {
                AnagrafeGiocatore g = giocatoriPerId.get(pid.asString());
                if (g != null) s.getRoster().add(g);
            }
            perId.put(testo(n, "id"), squadre.save(s));
        }
        return perId;
    }

    private Lega creaLega(Utente admin, JsonNode dati, Map<String, AnagrafeSquadra> squadrePerId) {
        Lega lega = new Lega(testo(dati, "lega"), admin);
        int posizione = 0;
        for (JsonNode n : dati.path("tappe")) {
            Tappa t = new Tappa();
            t.setId(UUID.randomUUID()); // casuale, come gli id che genera l'app: l'id corto del file non conta
            t.setLega(lega);
            t.setPosizione(posizione++);
            t.setNome(testo(n, "nome"));
            t.setLuogo(testo(n, "luogo"));
            t.setData(testo(n, "data"));
            t.setNGironi(n.path("nGironi").asInt(1));
            t.setConclusa(n.path("conclusa").asBoolean(false));
            t.setRegole(regole(n.path("regole")));
            t.setSquadre(collegaAnagrafe(n.path("squadre"), squadrePerId));
            t.setGironi(arrayONull(n.path("gironi")));
            t.setPartite(n.path("partite").toString());
            t.setBracket(arrayONull(n.path("bracket")));
            t.setVideo(n.path("video").toString());
            lega.getTappe().add(t);
        }
        return leghe.save(lega);
    }

    /**
     * Pubblica ogni tappa come farebbe "Pubblica in archivio" dal frontend: con lo stesso metodo del servizio, quindi
     * c'è un solo modo di costruire lo snapshot. Il seeder non ha un utente autenticato: pubblica l'admin, che della
     * lega è il proprietario.
     */
    private void pubblicaInArchivio(Utente admin, Lega lega) {
        for (Tappa t : lega.getTappe()) {
            archivioService.pubblica(admin, t.getId());
        }
    }

    /**
     * Aggiunge a ogni squadra iscritta il regId della scheda in anagrafe: così il frontend
     * la tratta come collegata (logo, rank e sito arrivano dall'anagrafe). L'id corto della
     * squadra dentro la tappa resta com'è: è locale alla tappa e lo usano partite e bracket.
     */
    private String collegaAnagrafe(JsonNode squadreTappa, Map<String, AnagrafeSquadra> squadrePerId) {
        for (JsonNode n : squadreTappa) {
            AnagrafeSquadra reg = squadrePerId.get(testo(n, "id"));
            if (reg != null && n instanceof ObjectNode obj) obj.put("regId", reg.getId().toString());
        }
        return squadreTappa.toString();
    }

    private static Regole regole(JsonNode n) {
        Regole r = new Regole();
        r.setTarget(n.path("target").asInt(21));
        r.setDurata(n.path("durata").asInt(10));
        r.setOt(n.path("ot").asInt(2));
        r.setShot(n.path("shot").asInt(12));
        return r;
    }

    /** gironi e bracket: NULL a database quando mancano nel file */
    private static String arrayONull(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return null;
        return n.toString();
    }

    private static String testo(JsonNode n, String campo) {
        return n.path(campo).asString("");
    }
}
