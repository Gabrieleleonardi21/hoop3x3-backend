package com.hoop3x3.backend;

import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.LegaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le colonne JSONB con il database vero: i blocchi di gioco di una tappa (squadre, gironi, partite, bracket, video) e il
 * contenuto di una pubblicazione si scrivono come testo JSON e devono tornare uguali, con ciò che un client può mandare
 * (annidamenti, null, numeri, accenti, emoji, virgolette e a capo). Devono stare nel database come JSON vero e non come una
 * stringa che contiene del JSON: è ciò che permette alla query dell'elenco dell'archivio di leggerne i campi (ArchivioIT).
 */
@TestDiIntegrazione
class JsonbIT {

    // Squadre con giocatori annidati, un null, accenti, ideogrammi, un'emoji, virgolette, barre rovesciate e a capo dentro i testi
    private static final String SQUADRE = """
            [{"id":"s1","nome":"Zoë Müller & figli","logo":null,
              "giocatori":[{"id":"g1","nome":"Zoë","numero":7},{"id":"g2","nome":"李雷 🏀","numero":10}]},
             {"id":"s2","nome":"Café \\"Milan\\" \\\\ Turin","note":"riga1\\nriga2\\tcon tab","giocatori":[]}]""";
    private static final String GIRONI = "[[\"s1\",\"s2\"],[\"s3\"]]";
    // Partite con eventi annidati, numeri interi, negativi e decimali, e un oggetto vuoto
    private static final String PARTITE = """
            [{"id":"m1","a":"s1","b":"s2","sa":21,"sb":17,"done":true,
              "eventi":[{"t":1.5,"tipo":"canestro","punti":2,"giocatore":null},{"t":-3,"tipo":"fallo"}],
              "stat":{"s1":{"rimbalzi":12,"perc":0.4167},"s2":{}}}]""";
    private static final String BRACKET = "[{\"round\":\"finale\",\"a\":\"s1\",\"b\":null,\"vincente\":null}]";
    private static final String VIDEO = "[{\"url\":\"https://example.org/v?x=1&y=2\",\"titolo\":\"Finale — 2ª parte\"}]";

    @Autowired LegaService legaService;
    @Autowired ArchivioService archivioService;
    @Autowired UtenteRepository utenti;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private Utente mario;

    @BeforeEach
    void creaIlProprietario() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
    }

    @Test
    void iCinqueBlocchiDiUnaTappaTornanoUgualiDalDatabase() {
        TappaDTO inviata = TappaDiProva.tappa().squadre(SQUADRE).gironi(GIRONI).partite(PARTITE).bracket(BRACKET).video(VIDEO).build();

        UUID lega = legaService.crea(mario, new NuovaLegaDTO("Circuito", List.of(inviata))).id();

        // Una lettura nuova: il servizio rilegge la tappa dal database, non dalla sessione che l'ha scritta
        TappaDTO letta = legaService.dettaglio(mario, lega).tappe().getFirst();
        assertThat(letta.squadre()).isEqualTo(inviata.squadre());
        assertThat(letta.gironi()).isEqualTo(inviata.gironi());
        assertThat(letta.partite()).isEqualTo(inviata.partite());
        assertThat(letta.bracket()).isEqualTo(inviata.bracket());
        assertThat(letta.video()).isEqualTo(inviata.video());
        // I testi speciali sono arrivati interi, non solo uguali nella forma
        assertThat(letta.squadre().at("/1/nome").asString()).isEqualTo("Café \"Milan\" \\ Turin");
        assertThat(letta.squadre().at("/1/note").asString()).isEqualTo("riga1\nriga2\tcon tab");
        assertThat(letta.squadre().at("/0/giocatori/1/nome").asString()).isEqualTo("李雷 🏀");
    }

    // Nel database sono JSON veri: un array è un array, non una stringa che ne contiene il testo
    @Test
    void iBlocchiSonoJsonVeroNelDatabase_nonStringheConDelJson() {
        TappaDTO tappa = TappaDiProva.tappa().squadre(SQUADRE).gironi(GIRONI).partite(PARTITE).bracket(BRACKET).video(VIDEO).build();
        legaService.crea(mario, new NuovaLegaDTO("Circuito", List.of(tappa)));

        Map<String, Object> tipi = jdbc.queryForMap("""
                select jsonb_typeof(squadre) as squadre, jsonb_typeof(gironi) as gironi, jsonb_typeof(partite) as partite,
                       jsonb_typeof(bracket) as bracket, jsonb_typeof(video) as video from tappe""");

        assertThat(tipi).containsOnly(Map.entry("squadre", "array"), Map.entry("gironi", "array"),
                Map.entry("partite", "array"), Map.entry("bracket", "array"), Map.entry("video", "array"));
        // E si interrogano con gli operatori di PostgreSQL, come fa l'elenco dell'archivio
        assertThat(jdbc.queryForObject("select squadre -> 0 ->> 'nome' from tappe", String.class)).isEqualTo("Zoë Müller & figli");
    }

    // Gironi e bracket possono mancare: «non ancora sorteggiati» è NULL nel database, e un array vuoto è un'altra cosa
    @Test
    void gironiEBracketAssentiSonoNullNelDatabase_unArrayVuotoNo() {
        TappaDTO senzaSorteggio = TappaDiProva.tappa().build();
        TappaDTO conGironiVuoti = TappaDiProva.tappa().gironi("[]").bracket("[]").build();
        UUID lega = legaService.crea(mario, new NuovaLegaDTO("Circuito", List.of(senzaSorteggio, conGironiVuoti))).id();

        assertThat(jdbc.queryForObject("select gironi is null and bracket is null from tappe where id = ?", Boolean.class,
                senzaSorteggio.id())).isTrue();
        assertThat(jdbc.queryForObject("select jsonb_typeof(gironi) = 'array' and jsonb_typeof(bracket) = 'array' from tappe where id = ?",
                Boolean.class, conGironiVuoti.id())).isTrue();
        List<TappaDTO> lette = legaService.dettaglio(mario, lega).tappe();
        assertThat(lette.get(0).gironi()).isNull();
        assertThat(lette.get(0).bracket()).isNull();
        assertThat(lette.get(1).gironi()).isEqualTo(mapper.readTree("[]"));
        assertThat(lette.get(1).bracket()).isEqualTo(mapper.readTree("[]"));
    }

    // Il giro completo di una tappa che cambia: da assente a presente e di nuovo assente (il sorteggio che si annulla)
    @Test
    void iGironiPassanoDaAssentiAPresentiEDiNuovoAssenti() {
        TappaDTO tappa = TappaDiProva.tappa().build();
        UUID lega = legaService.crea(mario, new NuovaLegaDTO("Circuito", List.of(tappa))).id();

        TappaDTO sorteggiata = legaService.aggiornaTappa(mario, tappa.id(), TappaDiProva.da(tappa).gironi(GIRONI).versione(0L).build());
        assertThat(sorteggiata.gironi()).isEqualTo(mapper.readTree(GIRONI));
        assertThat(legaService.dettaglio(mario, lega).tappe().getFirst().gironi()).isEqualTo(mapper.readTree(GIRONI));

        TappaDTO annullata = legaService.aggiornaTappa(mario, tappa.id(), TappaDiProva.da(sorteggiata).gironi(null).build());
        assertThat(annullata.gironi()).isNull();
        assertThat(jdbc.queryForObject("select gironi is null from tappe where id = ?", Boolean.class, tappa.id())).isTrue();
    }

    // La pubblicazione scrive la tappa come oggetto JSONB e la restituisce uguale a quella salvata, annidamenti e testi compresi
    @Test
    void ilContenutoDellaPubblicazioneEUnOggettoJsonbETornaUgualeAllaTappaSalvata() {
        TappaDTO tappa = TappaDiProva.tappa().squadre(SQUADRE).gironi(GIRONI).partite(PARTITE).bracket(BRACKET).video(VIDEO)
                .conclusa(true).build();
        UUID lega = legaService.crea(mario, new NuovaLegaDTO("Circuito", List.of(tappa))).id();

        archivioService.pubblica(mario, tappa.id());

        assertThat(jdbc.queryForObject("select jsonb_typeof(contenuto) from archivio_tappe", String.class)).isEqualTo("object");
        TappaDTO salvata = legaService.dettaglio(mario, lega).tappe().getFirst();
        assertThat(archivioService.una(tappa.id()).tappa()).isEqualTo(salvata);
    }
}
