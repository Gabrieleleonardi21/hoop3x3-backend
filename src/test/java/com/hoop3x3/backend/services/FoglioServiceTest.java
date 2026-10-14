package com.hoop3x3.backend.services;

import ch.qos.logback.classic.Level;
import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.UtenteDiProva;
import com.hoop3x3.backend.dto.BozzaFoglioDTO;
import com.hoop3x3.backend.dto.BozzaFoglioDTO.PartitaLetta;
import com.hoop3x3.backend.dto.BozzaFoglioDTO.SquadraLetta;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.exceptions.UpstreamException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La lettura di un foglio senza fornitore: il lettore è finto (Mockito). Si provano la scelta del fornitore, i controlli
 * sull'immagine prima di chiamarlo e la pulizia della bozza secondo i limiti del contratto (README, «Lettura dei fogli (foto)»).
 */
class FoglioServiceTest {

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0x0d};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
    private static final String BOZZA_MINIMA = "{\"squadre\":[],\"partite\":[],\"dubbi\":[]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Utente mario = UtenteDiProva.conId("mario@test.it");
    private final LettoreFoglio lettore = mock(LettoreFoglio.class);
    private FoglioService service;
    private LogCatturato log;

    @BeforeEach
    void conUnLettoreConfigurato() {
        log = new LogCatturato(FoglioService.class);
        when(lettore.isConfigurato()).thenReturn(true);
        when(lettore.leggi(any())).thenReturn(mapper.readTree(BOZZA_MINIMA));
        service = new FoglioService(lettore);
    }

    @AfterEach
    void chiudiIlLog() {
        log.close();
    }

    private static String dataUrl(String mime, byte[] dati) {
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(dati);
    }

    /** Il lettore risponde con quella bozza: la si legge dopo la pulizia */
    private BozzaFoglioDTO letta(String bozza) {
        when(lettore.leggi(any())).thenReturn(mapper.readTree(bozza));
        return service.leggi(mario, dataUrl("image/jpeg", JPEG));
    }

    private BozzaFoglioDTO letta(JsonNode bozza) {
        return letta(bozza.toString());
    }

    /** Una bozza vuota da riempire nel test */
    private ObjectNode bozza() {
        return (ObjectNode) mapper.readTree(BOZZA_MINIMA);
    }

    private void assertRifiutata(String immagine, String messaggio) {
        assertThatThrownBy(() -> service.leggi(mario, immagine)).isInstanceOf(BadRequestException.class).hasMessage(messaggio);
        verify(lettore, never()).leggi(any());
    }

    /* ── Lettura spenta ── */

    @Test
    void senzaLettore_risponde503SenzaChiamareNessuno() {
        service = new FoglioService((LettoreFoglio) null);

        assertThat(service.isConfigurato()).isFalse();
        assertThatThrownBy(() -> service.leggi(mario, dataUrl("image/jpeg", JPEG)))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .hasMessage(FoglioService.NON_CONFIGURATO);
    }

    @Test
    void unLettoreSenzaChiave_risponde503SenzaChiamarlo() {
        when(lettore.isConfigurato()).thenReturn(false);

        assertThat(service.isConfigurato()).isFalse();
        assertThatThrownBy(() -> service.leggi(mario, dataUrl("image/jpeg", JPEG)))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(lettore, never()).leggi(any());
    }

    /* ── Scelta del fornitore: ai.lettore-foglio ── */

    @Test
    void ilFornitoreSiScegliePerNome() {
        LettoreFoglioGroq groq = mock(LettoreFoglioGroq.class);
        LettoreFoglioAnthropic anthropic = mock(LettoreFoglioAnthropic.class);
        when(groq.isConfigurato()).thenReturn(true);
        when(anthropic.isConfigurato()).thenReturn(true);
        when(groq.leggi(any())).thenReturn(mapper.readTree(BOZZA_MINIMA));
        when(anthropic.leggi(any())).thenReturn(mapper.readTree(BOZZA_MINIMA));

        new FoglioService(new AiProperties("groq"), groq, anthropic).leggi(mario, dataUrl("image/jpeg", JPEG));
        verify(groq).leggi(any());
        new FoglioService(new AiProperties(" Anthropic "), groq, anthropic).leggi(mario, dataUrl("image/jpeg", JPEG));
        verify(anthropic).leggi(any());

        // Vuoto o un nome che non c'è: lettura spenta
        assertThat(new FoglioService(new AiProperties(""), groq, anthropic).isConfigurato()).isFalse();
        assertThat(new FoglioService(new AiProperties("openai"), groq, anthropic).isConfigurato()).isFalse();
    }

    /* ── L'immagine: data URL, tetto e primi byte, prima di chiamare il lettore ── */

    @ParameterizedTest
    @ValueSource(strings = {"immagine", "image/jpeg;base64,/9j/", "data:image/gif;base64,R0lGODlh", "data:image/jpeg,/9j/4AAQ",
            "data:application/pdf;base64,JVBERi0", "data:image/svg+xml;base64,PHN2Zz4=", "data:image/jpeg;base64,@@@@",
            "data:image/jpeg;base64,/9j/4A AQ", "data:image/jpeg;base64,", "DATA:IMAGE/JPEG;BASE64,/9j/4AAQ"})
    void unaStringaCheNonEUnDataUrlDiUnaFoto_risponde400Formato(String immagine) {
        assertRifiutata(immagine, FoglioService.FORMATO_NON_VALIDO);
    }

    // Il prefisso dice JPEG ma i byte sono altro: si guardano i primi byte, non il prefisso che sceglie il client
    @Test
    void unPrefissoCheNonCorrispondeAiByte_risponde400Formato() {
        assertRifiutata(dataUrl("image/jpeg", PNG), FoglioService.FORMATO_NON_VALIDO);
        assertRifiutata(dataUrl("image/png", JPEG), FoglioService.FORMATO_NON_VALIDO);
        assertRifiutata(dataUrl("image/jpeg", "%PDF-1.7 un documento".getBytes()), FoglioService.FORMATO_NON_VALIDO);
        assertRifiutata(dataUrl("image/webp", "RIFF0000WAVEfmt ".getBytes()), FoglioService.FORMATO_NON_VALIDO);
        assertRifiutata(dataUrl("image/jpeg", "testo qualsiasi".getBytes()), FoglioService.FORMATO_NON_VALIDO);
    }

    @Test
    void jpegPngEWebp_arrivanoAlLettoreConIByteEIlTipo() {
        ArgumentCaptor<ImmagineFoglio> immagine = ArgumentCaptor.forClass(ImmagineFoglio.class);

        service.leggi(mario, dataUrl("image/jpeg", JPEG));
        service.leggi(mario, dataUrl("image/png", PNG));
        service.leggi(mario, dataUrl("image/webp", WEBP));

        verify(lettore, times(3)).leggi(immagine.capture());
        assertThat(immagine.getAllValues()).extracting(ImmagineFoglio::mime).containsExactly("image/jpeg", "image/png", "image/webp");
        assertThat(immagine.getAllValues().getFirst().dati()).isEqualTo(JPEG);
    }

    // Il tetto si misura sul base64, prima di decodificare: 1.500.000 caratteri passano, uno in più no
    @Test
    void unBase64Di1500000Caratteri_passa_unoInPiu_risponde400TroppoGrande() {
        byte[] grande = Arrays.copyOf(JPEG, 1_125_000); // 1.125.000 byte sono esattamente 1.500.000 caratteri di base64
        String alLimite = dataUrl("image/jpeg", grande);
        assertThat(alLimite.length()).isEqualTo("data:image/jpeg;base64,".length() + FoglioService.MAX_BASE64);

        service.leggi(mario, alLimite);
        verify(lettore).leggi(any());

        // Un carattere in più che non è nemmeno base64: risponde il tetto, quindi non si è decodificato niente
        assertThatThrownBy(() -> service.leggi(mario, alLimite + "@"))
                .isInstanceOf(BadRequestException.class).hasMessage(FoglioService.TROPPO_GRANDE);
    }

    /* ── La bozza ripulita ── */

    @Test
    void unaBozzaPulita_arrivaComE() {
        BozzaFoglioDTO bozza = letta("""
                {"nome":" Tappa di Roma ","luogo":"Campo X","data":"2026-06-14",
                 "squadre":[{"nome":"Alfa","giocatori":["Mario Rossi","Luca Bianchi","Anna Verdi"]},{"nome":"Beta","giocatori":[]}],
                 "gironi":[["Alfa","Beta"]],
                 "partite":[{"a":"Alfa","b":"Beta","puntiA":21,"puntiB":15},{"a":"Beta","b":"Gamma","puntiA":null,"puntiB":0}],
                 "dubbi":["Partita Beta-Gamma: il punteggio di Beta non si legge"]}""");

        assertThat(bozza).isEqualTo(new BozzaFoglioDTO("Tappa di Roma", "Campo X", "2026-06-14",
                List.of(new SquadraLetta("Alfa", List.of("Mario Rossi", "Luca Bianchi", "Anna Verdi")), new SquadraLetta("Beta", List.of())),
                List.of(List.of("Alfa", "Beta")),
                List.of(new PartitaLetta("Alfa", "Beta", 21, 15), new PartitaLetta("Beta", "Gamma", null, 0)),
                List.of("Partita Beta-Gamma: il punteggio di Beta non si legge")));
    }

    @Test
    void campiAssentiONull_diventanoNullOElenchiVuoti() {
        BozzaFoglioDTO bozza = letta(BOZZA_MINIMA);

        assertThat(bozza).isEqualTo(new BozzaFoglioDTO(null, null, null, List.of(), null, List.of(), List.of()));
    }

    @Test
    void squadreSenzaNome_spariscono() {
        BozzaFoglioDTO bozza = letta("""
                {"squadre":[{"nome":"  ","giocatori":["Mario"]},{"giocatori":["Luca"]},{"nome":null},{"nome":42},"Alfa",
                 {"nome":"Beta","giocatori":["Anna"]}],"partite":[],"dubbi":[]}""");

        assertThat(bozza.squadre()).containsExactly(new SquadraLetta("Beta", List.of("Anna")));
    }

    @Test
    void testiLunghi_siTroncanoAlTettoDelContratto() {
        ObjectNode b = bozza();
        b.put("nome", "n".repeat(200)).put("luogo", "l".repeat(200));
        b.withArray("squadre").addObject().put("nome", "s".repeat(5_000)).putArray("giocatori").add("g".repeat(200));
        b.withArray("partite").addObject().put("a", "a".repeat(200)).put("b", "b".repeat(200)).put("puntiA", 1).put("puntiB", 2);
        b.withArray("dubbi").add("d".repeat(400));
        b.putArray("gironi").addArray().add("x".repeat(200));

        BozzaFoglioDTO bozza = letta(b);

        assertThat(bozza.nome()).hasSize(120);
        assertThat(bozza.luogo()).hasSize(160);
        assertThat(bozza.squadre().getFirst().nome()).hasSize(120);
        assertThat(bozza.squadre().getFirst().giocatori().getFirst()).hasSize(120);
        assertThat(bozza.partite().getFirst().a()).hasSize(120);
        assertThat(bozza.partite().getFirst().b()).hasSize(120);
        assertThat(bozza.dubbi().getFirst()).hasSize(300);
        assertThat(bozza.gironi().getFirst().getFirst()).hasSize(120);
    }

    @Test
    void punteggiFuoriScalaONonInteri_diventanoNull() {
        ObjectNode b = bozza();
        ArrayNode partite = b.withArray("partite");
        partite.addObject().put("a", "A").put("b", "B").put("puntiA", 1000).put("puntiB", -1);
        partite.addObject().put("a", "A").put("b", "B").put("puntiA", 21.5).put("puntiB", "21");
        partite.addObject().put("a", "A").put("b", "B").put("puntiA", 999).put("puntiB", 0);
        partite.addObject().put("a", "A").put("b", "B").put("puntiA", 99999999999L).putNull("puntiB");

        assertThat(letta(b).partite()).extracting(PartitaLetta::puntiA, PartitaLetta::puntiB).containsExactly(
                tuple(null, null), tuple(null, null),
                tuple(999, 0), tuple(null, null));
    }

    // Una partita senza una delle due squadre non si può importare: sparisce come una squadra senza nome
    @Test
    void partiteSenzaUnaSquadra_spariscono() {
        BozzaFoglioDTO bozza = letta("""
                {"squadre":[],"partite":[{"a":"Alfa","puntiA":21,"puntiB":15},{"a":" ","b":"Beta"},"Alfa-Beta",
                 {"a":"Alfa","b":"Beta","puntiA":21,"puntiB":15}],"dubbi":[]}""");

        assertThat(bozza.partite()).containsExactly(new PartitaLetta("Alfa", "Beta", 21, 15));
    }

    @ParameterizedTest
    @ValueSource(strings = {"14/06/2026", "2026-6-14", "domani", "2026-02-30", "2026-06-14T10:00"})
    void unaDataNonIso_diventaNullConIlDubbioDataNonLetta(String data) {
        ObjectNode b = bozza();
        b.put("data", data);
        b.withArray("dubbi").add("Un dubbio del modello");

        BozzaFoglioDTO bozza = letta(b);

        assertThat(bozza.data()).isNull();
        assertThat(bozza.dubbi()).containsExactly("Data non letta", "Un dubbio del modello");
    }

    // Non solo un testo scritto male: un numero, un oggetto, un elenco, un booleano o un testo vuoto non sono una data, e il
    // contratto vuole il dubbio per tutto ciò che non è null o assente
    @ParameterizedTest
    @ValueSource(strings = {"20260614", "{\"giorno\":14}", "[\"2026-06-14\"]", "true", "\"\"", "\"  \""})
    void unaDataCheNonEUnTesto_diventaNullConIlDubbioDataNonLetta(String data) {
        ObjectNode b = bozza();
        b.set("data", mapper.readTree(data));

        BozzaFoglioDTO bozza = letta(b);

        assertThat(bozza.data()).isNull();
        assertThat(bozza.dubbi()).containsExactly("Data non letta");
    }

    @Test
    void unaDataNullOAssente_restaNullSenzaDubbi() {
        assertThat(letta(BOZZA_MINIMA).dubbi()).isEmpty();
        ObjectNode b = bozza();
        b.putNull("data");

        assertThat(letta(b).dubbi()).isEmpty();
    }

    @Test
    void troppiElementi_siTengonoIPrimiFinoAlTetto() {
        ObjectNode b = bozza();
        for (int i = 0; i < 70; i++) b.withArray("squadre").addObject().put("nome", "S" + i).set("giocatori",
                mapper.valueToTree(Collections.nCopies(10, "G")));
        for (int i = 0; i < 40; i++) b.withArray("gironi").addArray().add("S" + i);
        for (int i = 0; i < 510; i++) b.withArray("partite").addObject().put("a", "S1").put("b", "S2").put("puntiA", i % 22).put("puntiB", 0);
        for (int i = 0; i < 60; i++) b.withArray("dubbi").add("Dubbio " + i);

        BozzaFoglioDTO bozza = letta(b);

        assertThat(bozza.squadre()).hasSize(64);
        assertThat(bozza.squadre().getFirst().nome()).isEqualTo("S0");
        assertThat(bozza.squadre().getLast().nome()).isEqualTo("S63");
        assertThat(bozza.squadre()).allSatisfy(s -> assertThat(s.giocatori()).hasSize(6));
        assertThat(bozza.gironi()).hasSize(32);
        assertThat(bozza.partite()).hasSize(500);
        assertThat(bozza.dubbi()).hasSize(50).startsWith("Dubbio 0").endsWith("Dubbio 49");
    }

    @Test
    void testiVuotiNegliElenchi_spariscono() {
        BozzaFoglioDTO bozza = letta("""
                {"squadre":[{"nome":"Alfa","giocatori":["Mario","  ",null,7,"Luca"]}],"gironi":[["Alfa"," "],"Beta"],
                 "partite":[],"dubbi":["", "  ", null, "Un dubbio"]}""");

        assertThat(bozza.squadre().getFirst().giocatori()).containsExactly("Mario", "Luca");
        assertThat(bozza.gironi()).containsExactly(List.of("Alfa"));
        assertThat(bozza.dubbi()).containsExactly("Un dubbio");
    }

    // Il lettore controlla già la forma, ma il service non si fida: senza la forma minima è un 502, mai un 500
    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "{\"squadre\":[],\"partite\":[],\"dubbi\":\"nessuno\"}", "{\"squadre\":null,\"partite\":[],\"dubbi\":[]}"})
    void unaBozzaSenzaLaFormaMinima_risponde502(String bozza) {
        assertThatThrownBy(() -> letta(bozza))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessage(LettoreFoglio.RISPOSTA_NON_VALIDA);
    }

    /* ── Il log: solo numeri, mai l'immagine né i nomi letti ── */

    @Test
    void unaLetturaRiuscita_lasciaUnaRigaInfoConINumeri() {
        letta("""
                {"squadre":[{"nome":"Alfa","giocatori":["Mario Rossi"]},{"nome":"Beta","giocatori":[]}],
                 "partite":[{"a":"Alfa","b":"Beta","puntiA":21,"puntiB":15}],"dubbi":["Un dubbio","Un altro"]}""");

        assertThat(log.livelli()).containsExactly(Level.INFO);
        assertThat(log.righe().getFirst())
                .matches("Foglio letto: utente " + mario.getId() + ", \\d+ KB, \\d+ ms, 2 squadre, 1 partite, 2 dubbi")
                .doesNotContain("Alfa").doesNotContain("Mario");
    }
}
