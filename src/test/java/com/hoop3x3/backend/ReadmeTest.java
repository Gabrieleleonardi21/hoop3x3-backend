package com.hoop3x3.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il README non deve marcire: i file che cita esistono, i collegamenti interni e l'indice combaciano con i titoli, l'elenco delle
 * migrazioni arriva all'ultima che c'è davvero e non restano codici interni dei piani di lavoro. Senza Spring e senza database:
 * legge solo README.md dalla radice del progetto (la cartella da cui Maven lancia i test) e la cartella delle migrazioni.
 */
class ReadmeTest {

    private static final Path README = Path.of("README.md");
    private static final Path MIGRAZIONI = Path.of("src/main/resources/db/migration");

    /** Un testo tra apici inversi, per esempio `src/main/resources/application.properties` */
    private static final Pattern TRA_APICI = Pattern.compile("`([^`]+)`");
    /** Un collegamento interno di Markdown, per esempio [Indice](#indice) */
    private static final Pattern COLLEGAMENTO_INTERNO = Pattern.compile("\\[[^\\]]*]\\(#([^)]+)\\)");
    /** Una voce di primo livello dell'indice: - [Titolo](#ancora) */
    private static final Pattern VOCE_INDICE = Pattern.compile("^- \\[(.+)]\\(#([^)]+)\\)\\s*$");
    /** Un file del repository citato con il suo percorso: sotto src/ o .github/ con un'estensione, oppure un file della radice */
    private static final Pattern PERCORSO_DI_FILE = Pattern.compile(
            "^((src/|\\.github/)\\S*\\.[A-Za-z0-9]+|Dockerfile|pom\\.xml|env\\.properties\\.example)$");
    private static final Pattern NOME_MIGRAZIONE = Pattern.compile("^V(\\d+)__.+\\.sql$");
    private static final Pattern INTERVALLO_MIGRAZIONI = Pattern.compile("V(\\d+)\\s*[–-]\\s*V(\\d+)");

    /** Le righe del README con il testo dei blocchi di codice (``` ... ```) tolto: dentro i blocchi un # non è un titolo */
    private static List<String> righeFuoriDaiBlocchi() throws IOException {
        List<String> risultato = new ArrayList<>();
        boolean dentroUnBlocco = false;
        for (String riga : Files.readAllLines(README, StandardCharsets.UTF_8)) {
            if (riga.startsWith("```")) {
                dentroUnBlocco = !dentroUnBlocco;
            } else if (!dentroUnBlocco) {
                risultato.add(riga);
            }
        }
        return risultato;
    }

    /** I titoli del livello dato (2 = "## ", 3 = "### "), senza i segni di Markdown, nell'ordine del file */
    private static List<String> titoli(int livello) throws IOException {
        String prefisso = "#".repeat(livello) + " ";
        List<String> risultato = new ArrayList<>();
        for (String riga : righeFuoriDaiBlocchi()) {
            if (riga.startsWith(prefisso)) {
                risultato.add(senzaSegni(riga.substring(prefisso.length())));
            }
        }
        return risultato;
    }

    private static String senzaSegni(String testo) {
        return testo.replace("`", "").replace("**", "").strip();
    }

    /** L'ancora che GitHub dà a un titolo: minuscole, solo lettere, cifre, trattini e underscore, gli spazi diventano trattini */
    private static String ancora(String titolo) {
        return titolo.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}_\\- ]", "")
                .replace(' ', '-');
    }

    /** Tutte le ancore del file; due titoli uguali prendono -1, -2... come fa GitHub */
    private static List<String> ancoreDelFile() throws IOException {
        List<String> risultato = new ArrayList<>();
        Map<String, Integer> viste = new HashMap<>();
        for (String riga : righeFuoriDaiBlocchi()) {
            Matcher m = Pattern.compile("^#{1,6} (.+)$").matcher(riga);
            if (m.matches()) {
                String base = ancora(senzaSegni(m.group(1)));
                int volte = viste.getOrDefault(base, 0);
                viste.put(base, volte + 1);
                if (volte == 0) {
                    risultato.add(base);
                } else {
                    risultato.add(base + "-" + volte);
                }
            }
        }
        return risultato;
    }

    private static int ultimaMigrazione() throws IOException {
        try (Stream<Path> file = Files.list(MIGRAZIONI)) {
            return file.map(p -> NOME_MIGRAZIONE.matcher(p.getFileName().toString()))
                    .filter(Matcher::matches)
                    .mapToInt(m -> Integer.parseInt(m.group(1)))
                    .max()
                    .orElseThrow();
        }
    }

    @Test
    void ogniFileCitatoNelReadmeEsiste() throws IOException {
        List<String> mancanti = new ArrayList<>();
        for (String riga : righeFuoriDaiBlocchi()) {
            Matcher m = TRA_APICI.matcher(riga);
            while (m.find()) {
                String testo = m.group(1);
                if (PERCORSO_DI_FILE.matcher(testo).matches() && !Files.exists(Path.of(testo))) {
                    mancanti.add(testo);
                }
            }
        }
        assertThat(mancanti).as("percorsi citati dal README che non esistono").isEmpty();
    }

    @Test
    void ogniCollegamentoInternoPuntaAUnTitolo() throws IOException {
        List<String> ancore = ancoreDelFile();
        List<String> rotti = new ArrayList<>();
        for (String riga : righeFuoriDaiBlocchi()) {
            Matcher m = COLLEGAMENTO_INTERNO.matcher(riga);
            while (m.find()) {
                if (!ancore.contains(m.group(1))) {
                    rotti.add("#" + m.group(1));
                }
            }
        }
        assertThat(rotti).as("collegamenti interni senza titolo").isEmpty();
    }

    @Test
    void lIndiceElencaTuttiITitoliDiSecondoLivelloNellOrdineDelFile() throws IOException {
        List<String> titoliDelFile = titoli(2);
        assertThat(titoliDelFile).as("il README ha una sezione Indice").contains("Indice");

        // Le voci di primo livello della sezione Indice, fino al titolo successivo
        List<String> vociTesto = new ArrayList<>();
        List<String> vociAncora = new ArrayList<>();
        boolean dentroLIndice = false;
        for (String riga : righeFuoriDaiBlocchi()) {
            if (riga.startsWith("## ")) {
                dentroLIndice = senzaSegni(riga.substring(3)).equals("Indice");
            } else if (dentroLIndice) {
                Matcher m = VOCE_INDICE.matcher(riga);
                if (m.matches()) {
                    vociTesto.add(m.group(1));
                    vociAncora.add(m.group(2));
                }
            }
        }

        List<String> attesi = new ArrayList<>(titoliDelFile);
        attesi.remove("Indice");
        assertThat(vociTesto).as("titoli ## nell'indice, in ordine").isEqualTo(attesi);
        List<String> ancoreAttese = attesi.stream().map(ReadmeTest::ancora).toList();
        assertThat(vociAncora).as("ancore dell'indice").isEqualTo(ancoreAttese);
    }

    @Test
    void leMigrazioniCitateArrivanoAllUltimaPresente() throws IOException {
        int ultima = ultimaMigrazione();
        String testo = String.join("\n", righeFuoriDaiBlocchi());

        // La sezione delle migrazioni dice fino a dove arrivano, con le parole che questo test cerca
        int inizio = testo.indexOf("\n## Migrazioni");
        assertThat(inizio).as("sezione Migrazioni").isNotNegative();
        int fine = testo.indexOf("\n## ", inizio + 1);
        String sezione = testo.substring(inizio, fine);
        assertThat(sezione).as("l'ultima migrazione presente è la V" + ultima)
                .containsPattern("da V1 a V" + ultima + "\\b");

        // Ogni intervallo scritto nel README (V1-V9, V2–V11...) finisce all'ultima migrazione: non resta indietro
        Matcher m = INTERVALLO_MIGRAZIONI.matcher(testo);
        while (m.find()) {
            assertThat(Integer.parseInt(m.group(2))).as("intervallo " + m.group()).isEqualTo(ultima);
        }
    }

    @Test
    void nessunCodiceInternoDeiPianiNeNomeDegliStrumentiDiSviluppo() throws IOException {
        String testo = String.join("\n", righeFuoriDaiBlocchi());
        // I codici dei piani di lavoro (BE-11, TR-4, T2.7...) si scrivono a parole: nei commenti del codice restano, qui no
        assertThat(testo).doesNotContainPattern("\\b(BE|TR|FE)-\\d+\\b");
        assertThat(testo).doesNotContainPattern("\\bT\\d+\\.\\d+\\b");
        // Il README parla del prodotto e del codice, non di chi o con che cosa è stato scritto
        assertThat(testo).doesNotContainPattern("(?i)\\b(claude|anthropic|gizmo|chatgpt|copilot|codex)\\b");
    }
}
