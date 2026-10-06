package com.hoop3x3.backend.runners;

import com.hoop3x3.backend.entities.*;
import com.hoop3x3.backend.repositories.*;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.UtenteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dati di prova del circuito Estathé 3x3 2025 (anagrafe, lega con 4 tappe concluse e archivio),
 * letti da resources/seed/estathe25.json. Attivo solo con SEED_DEMO=true e con l'admin configurato
 * (i dati vengono intestati a lui). Si esegue una sola volta: alla fine scrive il segno «demo» in seed_eseguiti, e al
 * riavvio lo riconosce anche se la lega demo è stata eliminata (con lei spariscono tappe e archivio, ma non giocatori e
 * squadre, che hanno id generati). Gli id corti del file ("p01", "s01", "t01") diventano UUID: quelli delle tappe sono
 * deterministici, e per i database seminati prima del segno la prima tappa demo dice che il seed è già stato fatto.
 * L'archivio lo riempie ArchivioService.pubblica, lo stesso metodo che usa l'app.
 */
@Slf4j
@Component
@Order(2) // dopo DataSeeder: serve l'admin già creato
public class DemoSeeder implements CommandLineRunner {

    private static final String FILE = "/seed/estathe25.json";
    /**
     * Il nome del segno in seed_eseguiti è quello dell'operazione, non dei dati demo (nome della lega, delle squadre, del
     * file): i dati possono cambiare e il segno deve restare riconoscibile.
     */
    private static final String SEGNO = "demo";

    private final UtenteRepository utenti;
    private final AnagrafeGiocatoreRepository giocatori;
    private final AnagrafeSquadraRepository squadre;
    private final LegaRepository leghe;
    private final TappaRepository tappe;
    private final SeedEseguitoRepository seedEseguiti;
    private final ArchivioService archivioService;
    private final ObjectMapper mapper;

    @Value("${seed.demo:false}")
    private boolean abilitato;
    @Value("${seed.admin.email:}")
    private String adminEmail;

    public DemoSeeder(UtenteRepository utenti, AnagrafeGiocatoreRepository giocatori, AnagrafeSquadraRepository squadre,
                      LegaRepository leghe, TappaRepository tappe, SeedEseguitoRepository seedEseguiti,
                      ArchivioService archivioService, ObjectMapper mapper) {
        this.utenti = utenti;
        this.giocatori = giocatori;
        this.squadre = squadre;
        this.leghe = leghe;
        this.tappe = tappe;
        this.seedEseguiti = seedEseguiti;
        this.archivioService = archivioService;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        // Ogni salto dice perché nei log: INFO se è la configurazione normale, avviso se chi ha acceso il seed non otterrà i dati
        if (!abilitato) {
            log.info("Seed demo saltato: SEED_DEMO non è true");
            return;
        }
        if (adminEmail.isBlank()) {
            log.warn("Seed demo saltato: SEED_DEMO è true ma ADMIN_EMAIL è vuota, e i dati di prova si intestano all'admin");
            return;
        }
        Utente admin = utenti.findByEmail(UtenteService.normalizza(adminEmail)).orElse(null);
        if (admin == null) {
            log.warn("Seed demo saltato: admin {} non trovato", adminEmail);
            return;
        }

        if (seedEseguiti.existsById(SEGNO)) {
            log.info("Seed demo saltato: già eseguito");
            return;
        }

        JsonNode dati;
        try (InputStream in = getClass().getResourceAsStream(FILE)) {
            dati = mapper.readTree(in);
        }
        // Un database seminato prima del segno non ce l'ha, ma ha ancora la prima tappa demo: il seed è già stato eseguito.
        // Il segno si scrive adesso, così da ora regge anche se la lega demo viene eliminata
        if (tappe.existsById(uuidPer(dati.path("tappe").path(0).path("id").asString()))) {
            seedEseguiti.save(new SeedEseguito(SEGNO));
            log.info("Seed demo saltato: già eseguito prima del segno (la prima tappa demo c'è), segno scritto");
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
            t.setId(uuidPer(testo(n, "id")));
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

    /** UUID stabile derivato dall'id corto del file (stesso input → stesso UUID a ogni avvio) */
    private static UUID uuidPer(String idCorto) {
        return UUID.nameUUIDFromBytes(("hoop3x3-seed-" + idCorto).getBytes(StandardCharsets.UTF_8));
    }
}
