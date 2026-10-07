package com.hoop3x3.backend.runners;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il file dei dati di prova (seed/estathe25.json) è coerente al suo interno: i nomi dei giocatori sono scritti nello stesso
 * modo nell'anagrafe, nelle squadre delle tappe e tra i referenti, e le statistiche citano solo giocatori che esistono.
 * Sono dati di fantasia (nessuna persona reale: TR-4): i nomi devono restare uguali ovunque compaiano, altrimenti la
 * stessa persona avrebbe due nomi. Una guardia dice se un valore originale (cognomi, altezza, peso, città, nazionalità
 * straniere) rientra nel file: le prove hanno solo le impronte SHA-256 di quei valori (dati-di-prova-originali.sha256), così
 * i dati veri non tornano in chiaro nel repository. Senza contesto Spring e senza database: legge solo il file.
 */
class DatiDiProvaTest {

    private JsonNode dati;
    /** Anagrafe per id corto del file ("p01"): nome e cognome di ogni giocatore */
    private final Map<String, JsonNode> giocatori = new HashMap<>();

    @BeforeEach
    void leggiIlFile() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/seed/estathe25.json")) {
            dati = JsonMapper.builder().build().readTree(in);
        }
        for (JsonNode g : dati.path("giocatori")) giocatori.put(g.path("id").asString(), g);
    }

    @Test
    void ilFileHa32GiocatoriE8Squadre() {
        assertThat(dati.path("giocatori")).hasSize(32);
        assertThat(dati.path("squadre")).hasSize(8);
    }

    // Nome e cognome sono scritti una volta sola nell'anagrafe: le tappe ripetono «nome cognome» con lo stesso id
    @Test
    void ogniGiocatoreInUnaTappaHaIlNomeCompletoDellAnagrafe() {
        int controllati = 0;
        for (JsonNode tappa : dati.path("tappe")) {
            for (JsonNode squadra : tappa.path("squadre")) {
                for (JsonNode g : squadra.path("giocatori")) {
                    JsonNode anagrafe = giocatori.get(g.path("id").asString());
                    assertThat(anagrafe).as("giocatore %s della tappa %s", g.path("id").asString(), tappa.path("id").asString())
                            .isNotNull();
                    assertThat(g.path("nome").asString()).isEqualTo(nomeCompleto(anagrafe));
                    controllati++;
                }
            }
        }
        assertThat(controllati).as("giocatori letti nelle squadre delle tappe").isEqualTo(128);
    }

    // Nelle squadre delle tappe un giocatore compare solo con la squadra del cui roster fa parte (stessi id corti del file)
    @Test
    void ogniGiocatoreDelleTappeStaNelRosterDellaSquadraDoveCompare() {
        int controllati = 0;
        Map<String, Set<String>> rosterPerSquadra = new HashMap<>();
        for (JsonNode s : dati.path("squadre")) {
            Set<String> ids = new HashSet<>();
            s.path("roster").forEach(id -> ids.add(id.asString()));
            rosterPerSquadra.put(s.path("id").asString(), ids);
        }
        for (JsonNode tappa : dati.path("tappe")) {
            for (JsonNode squadra : tappa.path("squadre")) {
                Set<String> roster = rosterPerSquadra.get(squadra.path("id").asString());
                for (JsonNode g : squadra.path("giocatori")) {
                    assertThat(roster).contains(g.path("id").asString());
                    controllati++;
                }
            }
        }
        assertThat(controllati).as("giocatori letti nelle squadre delle tappe").isEqualTo(128);
    }

    // Le statistiche delle partite sono per id del giocatore: devono richiamare giocatori che ci sono, e della squadra giusta
    @Test
    void leStatisticheCitanoSoloGiocatoriDellAnagrafeEDellaSquadraGiusta() {
        Map<String, List<String>> rosterPerSquadra = new HashMap<>();
        for (JsonNode s : dati.path("squadre")) {
            List<String> ids = new ArrayList<>();
            s.path("roster").forEach(id -> ids.add(id.asString()));
            rosterPerSquadra.put(s.path("id").asString(), ids);
        }
        int controllate = 0;
        for (JsonNode tappa : dati.path("tappe")) {
            for (JsonNode partita : tappa.path("partite")) {
                for (String lato : List.of("a", "b")) {
                    List<String> roster = rosterPerSquadra.get(partita.path(lato).asString());
                    for (String id : partita.path("p" + lato).propertyNames()) {
                        assertThat(giocatori).containsKey(id);
                        assertThat(roster).contains(id);
                        controllate++;
                    }
                }
            }
        }
        assertThat(controllate).as("righe di statistiche lette").isEqualTo(384);
    }

    // Il referente di una squadra è una persona con nome e cognome: quasi sempre uno del suo roster, e nello stesso modo in cui
    // lo scrive l'anagrafe; un referente che non è del roster non deve coincidere con nessun giocatore di un'altra squadra
    @Test
    void ogniReferenteHaNomeECognome_eSeELoDelRosterEScrittoComeInAnagrafe() {
        Set<String> nomiDeiGiocatori = new HashSet<>();
        giocatori.values().forEach(g -> nomiDeiGiocatori.add(nomeCompleto(g)));
        Set<String> referenti = new HashSet<>();
        for (JsonNode s : dati.path("squadre")) {
            String referente = s.path("referente").asString();
            assertThat(referente).as("referente di %s", s.path("nome").asString()).contains(" ").isEqualTo(referente.trim());
            referenti.add(referente);
            if (nomiDeiGiocatori.contains(referente)) {
                // È un giocatore: del roster di questa squadra
                List<String> delRoster = new ArrayList<>();
                s.path("roster").forEach(id -> delRoster.add(nomeCompleto(giocatori.get(id.asString()))));
                assertThat(delRoster).as("il referente giocatore di %s è del suo roster", s.path("nome").asString())
                        .contains(referente);
            }
        }
        assertThat(referenti).as("referenti tutti diversi").hasSize(8);
    }

    @Test
    void ogniGiocatoreHaUnNomeCompletoDiverso_eUnaDataDiNascitaValida() {
        Set<String> nomi = new HashSet<>();
        for (JsonNode g : giocatori.values()) {
            assertThat(nomi.add(nomeCompleto(g))).as("nome ripetuto: %s", nomeCompleto(g)).isTrue();
            // Una data ISO vera, di una persona adulta e non nata nel futuro rispetto alla stagione dei dati (2025)
            LocalDate nascita = LocalDate.parse(g.path("nascita").asString());
            assertThat(nascita).isBetween(LocalDate.of(1975, 1, 1), LocalDate.of(2008, 12, 31));
        }
    }

    // Il campo «squadra» di un giocatore è il nome della squadra del cui roster fa parte: il frontend le abbina per nome
    // (per esempio per il logo), quindi un nome scritto in un altro modo nell'anagrafe e nelle squadre le separerebbe
    @Test
    void ilCampoSquadraDiOgniGiocatoreELNomeDellaSquadraDelSuoRoster() {
        Map<String, String> squadraPerGiocatore = new HashMap<>();
        for (JsonNode s : dati.path("squadre")) {
            s.path("roster").forEach(id -> squadraPerGiocatore.put(id.asString(), s.path("nome").asString()));
        }
        assertThat(squadraPerGiocatore).as("giocatori nei roster").hasSize(32);
        for (JsonNode g : giocatori.values()) {
            assertThat(g.path("squadra").asString()).as("squadra di %s", nomeCompleto(g))
                    .isEqualTo(squadraPerGiocatore.get(g.path("id").asString()));
        }
    }

    /* ── TR-4: nessun dato originale delle persone (confronto con le impronte, mai con i dati in chiaro) ── */

    // Nessuna parola del file, né coppia di parole vicine (i cognomi doppi), ha l'impronta di uno dei 32 cognomi originali
    @Test
    void nelFileNonCEUnCognomeOriginale() throws Exception {
        String testo = dati.toString();

        assertThat(corrispondenze(testo, impronteOriginali())).isEmpty();
    }

    // Altezza, peso, città e nazionalità sono di fantasia: nessun giocatore ha ancora un valore originale (la coppia squadra e
    // altezza bastava a riconoscere la persona). Le città vuote e la nazionalità ITA, comune a quasi tutti, non sono nel confronto
    @Test
    void nessunGiocatoreHaAncoraAltezzaPesoCittaONazionalitaOriginali() throws Exception {
        Set<String> originali = impronteOriginali();
        List<String> rimasti = new ArrayList<>();
        for (JsonNode g : giocatori.values()) {
            for (String campo : List.of("altezza", "peso", "citta", "nazionalita")) {
                String chiave = campo + "|" + g.path("id").asString() + "|" + g.path(campo).asString();
                if (originali.contains(sha256(chiave))) rimasti.add(campo + " di " + g.path("id").asString());
            }
        }
        assertThat(rimasti).isEmpty();
    }

    // Il rilevatore funziona: con le impronte di due cognomi inventati trova le parole e le coppie di parole che li scrivono,
    // in qualunque maiuscolo, apostrofi compresi, e non confonde una parola che li contiene soltanto
    @Test
    void ilRilevatoreTrovaUnCognomeSoloOSuDueParoleEIgnoraIResto() throws Exception {
        Set<String> impronte = Set.of(sha256("rossi"), sha256("de luca"), sha256("d'angelo"));

        assertThat(corrispondenze("Mario ROSSI e Anna De Luca, detta D'Angelo", impronte))
                .containsExactlyInAnyOrder("rossi", "de luca", "d'angelo");
        assertThat(corrispondenze("Rossini, Deluca e D'Angelone", impronte)).isEmpty();
    }

    /** Le parole (con gli apostrofi dentro) e le coppie di parole vicine di `testo`, in minuscolo, la cui impronta è in `impronte` */
    private static List<String> corrispondenze(String testo, Set<String> impronte) throws NoSuchAlgorithmException {
        String[] parole = testo.toLowerCase(Locale.ROOT).split("[^\\p{L}'’]+");
        List<String> trovate = new ArrayList<>();
        for (int i = 0; i < parole.length; i++) {
            if (impronte.contains(sha256(parole[i]))) trovate.add(parole[i]);
            if (i + 1 < parole.length && impronte.contains(sha256(parole[i] + " " + parole[i + 1]))) {
                trovate.add(parole[i] + " " + parole[i + 1]);
            }
        }
        return trovate;
    }

    /** Le impronte dei valori originali, una per riga (le righe che cominciano per # sono commenti) */
    private Set<String> impronteOriginali() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/dati-di-prova-originali.sha256")) {
            Set<String> impronte = new HashSet<>();
            for (String riga : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!riga.isBlank() && !riga.startsWith("#")) impronte.add(riga.trim());
            }
            assertThat(impronte).as("impronte dei valori originali").hasSize(127);
            return impronte;
        }
    }

    private static String sha256(String testo) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(testo.getBytes(StandardCharsets.UTF_8)));
    }

    private static String nomeCompleto(JsonNode giocatore) {
        return giocatore.path("nome").asString() + " " + giocatore.path("cognome").asString();
    }
}
