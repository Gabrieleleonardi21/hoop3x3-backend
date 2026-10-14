package com.hoop3x3.backend.services;

import ch.qos.logback.classic.Level;
import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.exceptions.UpstreamException;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lettura del foglio con Groq: la forma della richiesta (modello con immagini, schema stretto) e gli errori. Groq è simulato
 * da GroqFinto: il servizio usa il suo vero client HTTP, ma i test non escono dal computer e non servono chiavi vere.
 */
class LettoreFoglioGroqTest {

    private static final String CHIAVE = "gsk_finta_foglio";
    // Una PNG di prova: i primi byte veri e qualcosa dopo. I controlli sul formato li fa FoglioService, qui conta che arrivi
    private static final ImmagineFoglio IMMAGINE = new ImmagineFoglio(
            new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3}, "image/png");
    private static final String BOZZA = """
            {"nome":"Tappa di Roma","luogo":null,"data":"2026-06-14","squadre":[{"nome":"Alfa","giocatori":["Mario"]}],
             "gironi":null,"partite":[{"a":"Alfa","b":"Beta","puntiA":21,"puntiB":15}],"dubbi":[]}""";
    private static final String CORPO_DI_ERRORE = "{\"error\":{\"message\":\"dettaglio interno di Groq\"}}";
    private static final Duration TIMEOUT_BREVE = Duration.ofSeconds(1);

    private final ObjectMapper mapper = new ObjectMapper();
    private LogCatturato log;
    private GroqFinto groq;
    private LettoreFoglioGroq lettore;

    @BeforeEach
    void conGroqFinto() throws IOException {
        log = new LogCatturato(LettoreFoglioGroq.class);
        groq = new GroqFinto();
        lettore = nuovoLettore(CHIAVE, Duration.ofSeconds(5));
    }

    @AfterEach
    void fermaGroqFintoEControllaIlLog() {
        groq.close();
        log.close();
        // Qualunque cosa sia successa, nei log non finiscono né la chiave né l'immagine
        assertThat(log.righe()).noneSatisfy(riga -> assertThat(riga).contains(CHIAVE));
        assertThat(log.righe()).noneSatisfy(riga -> assertThat(riga).contains(IMMAGINE.base64()));
    }

    private LettoreFoglioGroq nuovoLettore(String chiave, Duration timeoutRisposta) {
        GroqProperties proprieta = new GroqProperties(new GroqProperties.Api(chiave), "modello-del-coach", "low", "modello-con-immagini");
        return new LettoreFoglioGroq(mapper, proprieta, groq.url(), Duration.ofSeconds(5), timeoutRisposta);
    }

    /** La risposta di Groq con la bozza nel content del primo choice, come testo JSON */
    private String rispostaCon(String content) {
        return mapper.createObjectNode().set("choices", mapper.createArrayNode().add(mapper.createObjectNode()
                .set("message", mapper.createObjectNode().put("content", content)))).toString();
    }

    private AbstractThrowableAssert<?, ? extends Throwable> assertFallisceCon(HttpStatus stato) {
        return assertThatThrownBy(() -> lettore.leggi(IMMAGINE))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(stato));
    }

    /** L'unica riga di log, di livello WARN */
    private String unicaRigaDiLog() {
        assertThat(log.livelli()).containsExactly(Level.WARN);
        return log.righe().getFirst();
    }

    @Test
    void conLaChiave_eConfigurato_senzaNo() {
        assertThat(lettore.isConfigurato()).isTrue();
        assertThat(nuovoLettore("", Duration.ofSeconds(5)).isConfigurato()).isFalse();
    }

    @Test
    void laRichiesta_haChiaveModelloConImmaginiPromptFotoESchemaStretto() {
        groq.rispondi(200, rispostaCon(BOZZA));

        lettore.leggi(IMMAGINE);

        assertThat(groq.richieste()).singleElement().satisfies(r -> {
            assertThat(r.autorizzazione()).isEqualTo("Bearer " + CHIAVE);
            JsonNode inviato = mapper.readTree(r.corpo());
            assertThat(inviato.path("model").asString()).isEqualTo("modello-con-immagini");
            assertThat(inviato.path("temperature").asInt()).isZero();
            assertThat(inviato.path("max_tokens").asInt()).isEqualTo(4000);
            // Niente ragionamento né strumenti: la risposta è solo la bozza nello schema
            assertThat(inviato.has("reasoning_effort")).isFalse();
            assertThat(inviato.has("tools")).isFalse();
            assertThat(inviato.path("messages")).hasSize(1);
            assertThat(inviato.at("/messages/0/role").asString()).isEqualTo("user");
            JsonNode parti = inviato.at("/messages/0/content");
            assertThat(parti).hasSize(2);
            assertThat(parti.at("/0/type").asString()).isEqualTo("text");
            assertThat(parti.at("/0/text").asString()).isEqualTo(SchemaFoglio.PROMPT);
            assertThat(parti.at("/1/type").asString()).isEqualTo("image_url");
            assertThat(parti.at("/1/image_url/url").asString()).isEqualTo("data:image/png;base64," + IMMAGINE.base64());
            JsonNode formato = inviato.path("response_format");
            assertThat(formato.path("type").asString()).isEqualTo("json_schema");
            assertThat(formato.at("/json_schema/name").asString()).isEqualTo("foglio_tappa");
            assertThat(formato.at("/json_schema/strict").asBoolean()).isTrue();
            assertThat(formato.at("/json_schema/schema")).isEqualTo(mapper.readTree(SchemaFoglio.SCHEMA));
        });
    }

    @Test
    void unaRispostaConLaBozzaNelContent_restituisceLaBozza() {
        groq.rispondi(200, rispostaCon(BOZZA));

        assertThat(lettore.leggi(IMMAGINE)).isEqualTo(mapper.readTree(BOZZA));
        assertThat(log.righe()).as("una lettura riuscita la registra FoglioService").isEmpty();
    }

    // Il content non è la bozza: testo, JSON a metà, un oggetto senza gli elenchi
    @ParameterizedTest
    @ValueSource(strings = {"Non riesco a leggere il foglio", "{\"squadre\":[", "{\"partite\":[],\"dubbi\":[]}",
            "{\"squadre\":{},\"partite\":[],\"dubbi\":[]}", "{\"squadre\":[],\"partite\":[],\"dubbi\":\"nessuno\"}", "[]"})
    void unContentCheNonEUnaBozza_risponde502RispostaNonValida(String content) {
        groq.rispondi(200, rispostaCon(content));

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.RISPOSTA_NON_VALIDA);
        assertThat(unicaRigaDiLog()).contains(content.length() + " caratteri");
    }

    // Una bozza a metà porta i nomi letti dal foglio, cioè persone: nel log vanno solo la lunghezza, le chiavi di primo
    // livello e il motivo della fine della risposta, mai il contenuto
    @Test
    void unaBozzaNonValida_nelLogNonPortaINomiLetti() {
        String content = "{\"squadre\":[{\"nome\":\"Alfa\",\"giocatori\":[\"Mario Rossi\"]}],\"dubbi\":[\"Anna Verdi\"]}";
        groq.rispondi(200, mapper.createObjectNode().set("choices", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("finish_reason", "length").set("message", mapper.createObjectNode().put("content", content)))).toString());

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.RISPOSTA_NON_VALIDA);
        assertThat(unicaRigaDiLog()).contains(content.length() + " caratteri").contains("[squadre, dubbi]").contains("length")
                .doesNotContain("Mario Rossi").doesNotContain("Anna Verdi").doesNotContain("Alfa");
    }

    // La risposta di Groq stessa non ha un content di testo, o non è JSON
    @ParameterizedTest
    @ValueSource(strings = {"{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":null}}]}", "<html>Bad gateway</html>", ""})
    void unaRispostaSenzaContent_risponde502RispostaNonValida(String corpo) {
        groq.rispondi(200, corpo);

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.RISPOSTA_NON_VALIDA);
        assertThat(unicaRigaDiLog()).contains(corpo.length() + " caratteri");
    }

    @Test
    void groq429_risponde429ConIlLimiteDelLettore() {
        groq.rispondi(429, CORPO_DI_ERRORE);

        assertFallisceCon(HttpStatus.TOO_MANY_REQUESTS).hasMessage(LettoreFoglio.LIMITE_FORNITORE);
        assertThat(unicaRigaDiLog()).contains("429").contains(CORPO_DI_ERRORE);
    }

    // Anche un 400 di Groq (schema rifiutato, modello dismesso) è un guasto del server, non della foto: 502, non 400
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 413, 422, 500, 503})
    void altriErroriDiGroq_risponde502ConUnMessaggioNostro(int stato) {
        groq.rispondi(stato, CORPO_DI_ERRORE);

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.FORNITORE_NON_DISPONIBILE);
        assertThat(unicaRigaDiLog()).contains(String.valueOf(stato)).contains(CORPO_DI_ERRORE);
    }

    @Test
    void corpoDiErroreLungo_neiLogSiTroncaA500Caratteri() {
        groq.rispondi(500, "a".repeat(500) + "#".repeat(100));

        assertFallisceCon(HttpStatus.BAD_GATEWAY);
        assertThat(unicaRigaDiLog()).contains("a".repeat(500) + "…").doesNotContain("#");
    }

    // Se un errore riportasse la richiesta, l'immagine resterebbe fuori dal log: si toglie tutto ciò che segue «base64,»
    @Test
    void unErroreCheRiportaLImmagine_neiLogNonLaScrive() {
        groq.rispondi(400, "{\"error\":\"url non valido: data:image/png;base64," + IMMAGINE.base64() + "\"}");

        assertFallisceCon(HttpStatus.BAD_GATEWAY);
        assertThat(unicaRigaDiLog()).contains("data:image/png;base64,").doesNotContain(IMMAGINE.base64());
    }

    @Test
    void groqNonRaggiungibile_risponde502() {
        groq.close();

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.FORNITORE_NON_RAGGIUNGIBILE);
        assertThat(unicaRigaDiLog()).contains("non raggiungibile");
    }

    @Test
    @Timeout(10)
    void groqCheNonRisponde_dopoIlTimeout_risponde502() {
        groq.nonRispondeMai();
        lettore = nuovoLettore(CHIAVE, TIMEOUT_BREVE);

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.FORNITORE_NON_RAGGIUNGIBILE);
    }
}
