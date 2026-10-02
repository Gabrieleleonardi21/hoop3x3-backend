package com.hoop3x3.backend.services;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.hoop3x3.backend.dto.CoachChatRequestDTO;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.exceptions.UpstreamException;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coach AI: controlli sulla richiesta e gestione degli errori di Groq. Groq è simulato da un server HTTP locale
 * (GroqFinto): il servizio usa il suo vero client HTTP, ma i test non escono dal computer e non servono chiavi vere.
 */
class CoachAiServiceTest {

    private static final String CHIAVE = "gsk_finta";
    private static final String MESSAGGI_VALIDI = "[{\"role\":\"user\",\"content\":\"ciao\"}]";
    private static final String TOOL_VALIDI = "[{\"type\":\"function\",\"function\":{\"name\":\"crea_tappa\"}}]";
    // Cornici per costruire elenchi di una lunghezza esatta, con in mezzo il riempimento
    private static final String INIZIO_MESSAGGIO = "[{\"role\":\"user\",\"content\":\"";
    private static final String INIZIO_STRUMENTO = "[{\"type\":\"function\",\"description\":\"";
    private static final String FINE = "\"}]";
    // Corpo di un errore di Groq: al client non deve arrivare, nei log sì
    private static final String DETTAGLIO_DI_GROQ = "dettaglio interno di Groq";
    private static final String CORPO_DI_ERRORE = "{\"error\":{\"message\":\"" + DETTAGLIO_DI_GROQ + "\"}}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logDelServizio = (Logger) LoggerFactory.getLogger(CoachAiService.class);
    private final ListAppender<ILoggingEvent> logCatturato = new ListAppender<>();
    private GroqFinto groq;
    private CoachAiService service;

    @BeforeEach
    void conGroqFintoEChiaveConfigurata() throws IOException {
        // Le righe di log del servizio si leggono dal test e non passano dalla console: l'output della build resta pulito
        logCatturato.start();
        logDelServizio.addAppender(logCatturato);
        logDelServizio.setAdditive(false);
        groq = new GroqFinto();
        service = nuovoServizio(Duration.ofSeconds(5));
    }

    @AfterEach
    void fermaGroqFintoEControllaIlLog() {
        groq.close();
        logDelServizio.detachAppender(logCatturato);
        logDelServizio.setAdditive(true);
        // Qualunque cosa sia successa nel test, la chiave non deve comparire in nessuna riga di log
        assertThat(logCatturato.list).noneSatisfy(riga -> assertThat(testo(riga)).contains(CHIAVE));
    }

    /** Servizio puntato sempre a Groq finto, mai a quello vero; chiave e modello finti */
    private CoachAiService nuovoServizio(Duration timeoutRisposta) {
        CoachAiService s = new CoachAiService(mapper, groq.url(), Duration.ofSeconds(5), timeoutRisposta);
        ReflectionTestUtils.setField(s, "apiKey", CHIAVE);
        ReflectionTestUtils.setField(s, "model", "modello-di-test");
        return s;
    }

    private CoachChatRequestDTO richiesta(String messages, String tools) {
        JsonNode t = null;
        if (tools != null) t = mapper.readTree(tools);
        return new CoachChatRequestDTO(mapper.readTree(messages), t);
    }

    /** Respinta con 400 prima di chiamare Groq: l'eccezione restituita serve a controllare il messaggio */
    private AbstractThrowableAssert<?, ? extends Throwable> assertRifiutata(String messages, String tools) {
        int arrivateAGroq = groq.richieste().size();
        AbstractThrowableAssert<?, ? extends Throwable> eccezione = assertThatThrownBy(() -> service.chat(richiesta(messages, tools)))
                .isInstanceOf(BadRequestException.class);
        assertThat(groq.richieste()).as("una richiesta respinta non deve arrivare a Groq").hasSize(arrivateAGroq);
        return eccezione;
    }

    /** Accettata: arriva a Groq (finto) senza eccezioni */
    private void assertAccettata(String messages, String tools) {
        int arrivateAGroq = groq.richieste().size();
        service.chat(richiesta(messages, tools));
        assertThat(groq.richieste()).hasSize(arrivateAGroq + 1);
    }

    /** Una richiesta valida fallisce con un UpstreamException dello stato dato: l'eccezione serve a controllare il messaggio */
    private AbstractThrowableAssert<?, ? extends Throwable> assertFallisceCon(HttpStatus stato) {
        return assertThatThrownBy(() -> service.chat(richiesta(MESSAGGI_VALIDI, null)))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(stato));
    }

    /** Elenco JSON lungo esattamente `caratteri` caratteri: la cornice data, con in mezzo il riempimento di «x» */
    private static String lungo(String inizio, int caratteri) {
        return inizio + "x".repeat(caratteri - inizio.length() - FINE.length()) + FINE;
    }

    /** Elenco di `quanti` elementi tutti uguali */
    private static String elenco(String elemento, int quanti) {
        return "[" + String.join(",", Collections.nCopies(quanti, elemento)) + "]";
    }

    /** L'unica riga di log scritta dal servizio, di livello WARN: messaggio ed eventuale catena di eccezioni, come testo */
    private String unicaRigaDiLog() {
        assertThat(logCatturato.list).singleElement().satisfies(riga -> assertThat(riga.getLevel()).isEqualTo(Level.WARN));
        return testo(logCatturato.list.getFirst());
    }

    private static String testo(ILoggingEvent riga) {
        String testo = riga.getFormattedMessage();
        if (riga.getThrowableProxy() != null) testo += "\n" + ThrowableProxyUtil.asString(riga.getThrowableProxy());
        return testo;
    }

    /* ── Controlli sulla richiesta: nessuno di questi casi deve arrivare a Groq ── */

    @Test
    void senzaChiave_risponde503() {
        ReflectionTestUtils.setField(service, "apiKey", "");
        assertThatThrownBy(() -> service.chat(richiesta(MESSAGGI_VALIDI, null)))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(groq.richieste()).isEmpty();
    }

    @Test
    void senzaChiave_lasciaUnaRigaDiLogConLaCausa() {
        ReflectionTestUtils.setField(service, "apiKey", "");
        assertFallisceCon(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unicaRigaDiLog()).contains("groq.api.key");
    }

    @Test
    void messagesNonArray_rifiutato() {
        assertRifiutata("{\"role\":\"user\"}", null);
    }

    @Test
    void messagesNull_rifiutato() {
        // Un null JSON arriva come NullNode, che @NotNull sul DTO non intercetta
        assertRifiutata("null", null);
    }

    @Test
    void messagesVuoto_rifiutato() {
        assertRifiutata("[]", null);
    }

    @Test
    void ruoloSconosciuto_rifiutato() {
        assertRifiutata("[{\"role\":\"developer\",\"content\":\"x\"}]", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"ciao\"]",                               // un testo al posto di un oggetto
            "[null]",
            "[{\"content\":\"x\"}]",                    // senza ruolo
            "[{\"role\":null,\"content\":\"x\"}]",
            "[{\"role\":5,\"content\":\"x\"}]",         // ruolo non testuale
            "[{\"role\":\"User\",\"content\":\"x\"}]"   // i ruoli sono quelli di OpenAI, in minuscolo
    })
    void messaggioSenzaRuoloValido_rifiutato(String messages) {
        assertRifiutata(messages, null).hasMessageContaining("ruolo");
    }

    @Test
    void conversazioneTroppoLunga_rifiutata() {
        String enorme = "x".repeat(100_001);
        assertRifiutata("[{\"role\":\"user\",\"content\":\"" + enorme + "\"}]", null).hasMessageContaining("troppo lunga");
    }

    @Test
    void conversazione_100000CaratteriSiAccettano_100001No() {
        assertAccettata(lungo(INIZIO_MESSAGGIO, 100_000), null);
        assertRifiutata(lungo(INIZIO_MESSAGGIO, 100_001), null).hasMessageContaining("troppo lunga");
    }

    @Test
    void messaggi_sessantaSiAccettano_sessantunoNo() {
        assertAccettata(elenco("{\"role\":\"user\",\"content\":\"x\"}", 60), null);
        assertRifiutata(elenco("{\"role\":\"user\",\"content\":\"x\"}", 61), null).hasMessageContaining("60");
    }

    @Test
    void toolsNonArray_rifiutato() {
        assertRifiutata(MESSAGGI_VALIDI, "\"non un array\"");
    }

    @Test
    void tools_ventiSiAccettano_ventunoNo() {
        assertAccettata(MESSAGGI_VALIDI, elenco("{\"type\":\"function\"}", 20));
        assertRifiutata(MESSAGGI_VALIDI, elenco("{\"type\":\"function\"}", 21)).hasMessageContaining("20");
    }

    @Test
    void toolsOltre50000Caratteri_rifiutati() {
        assertAccettata(MESSAGGI_VALIDI, lungo(INIZIO_STRUMENTO, 50_000));
        assertRifiutata(MESSAGGI_VALIDI, lungo(INIZIO_STRUMENTO, 50_001)).hasMessageContaining("tools");
    }

    /* ── Richieste valide ── */

    @Test
    void conversazioneConStrumenti_accettata() {
        // Come la manda il Coach dopo una chiamata di strumento: assistant con content null, poi il risultato con ruolo tool
        assertAccettata("""
                [{"role":"system","content":"Sei il Coach"},
                 {"role":"user","content":"crea la tappa"},
                 {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"crea_tappa","arguments":"{}"}}]},
                 {"role":"tool","tool_call_id":"c1","content":"Tappa creata"}]""", TOOL_VALIDI);
    }

    @Test
    void richiestaValida_arrivaAGroqConChiaveEModelloDelServer() {
        groq.rispondi(200, "{\"choices\":[{\"message\":{\"content\":\"Ciao!\"}}]}");

        JsonNode risposta = service.chat(richiesta(MESSAGGI_VALIDI, TOOL_VALIDI));

        assertThat(risposta.at("/choices/0/message/content").asString()).isEqualTo("Ciao!");
        assertThat(groq.richieste()).singleElement().satisfies(r -> {
            assertThat(r.autorizzazione()).isEqualTo("Bearer " + CHIAVE);
            JsonNode inviato = mapper.readTree(r.corpo());
            // Modello e limite di token li fissa il server: il browser manda solo messages e tools
            assertThat(inviato.path("model").asString()).isEqualTo("modello-di-test");
            assertThat(inviato.path("max_tokens").asInt()).isEqualTo(1200);
            assertThat(inviato.path("reasoning_effort").asString()).isEqualTo("low");
            assertThat(inviato.path("tool_choice").asString()).isEqualTo("auto");
            assertThat(inviato.path("messages").toString()).isEqualTo(MESSAGGI_VALIDI);
            assertThat(inviato.path("tools").toString()).isEqualTo(TOOL_VALIDI);
        });
        assertThat(logCatturato.list).as("una richiesta riuscita non scrive nei log").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]"})
    void toolsAssentiOVuoti_accettatiENonInoltrati(String tools) {
        // Il frontend manda tools: [] quando non ci sono strumenti: Groq non deve ricevere né tools né tool_choice
        service.chat(richiesta(MESSAGGI_VALIDI, tools));

        JsonNode inviato = mapper.readTree(groq.richieste().getFirst().corpo());
        assertThat(inviato.has("tools")).isFalse();
        assertThat(inviato.has("tool_choice")).isFalse();
    }

    /* ── Errori di Groq: al client un messaggio nostro, stato e corpo nei log ── */

    @Test
    void groq429_alClientRisponde429() {
        groq.rispondi(429, CORPO_DI_ERRORE);

        assertFallisceCon(HttpStatus.TOO_MANY_REQUESTS)
                .hasMessageContaining("Limite richieste")
                .hasMessageNotContaining(DETTAGLIO_DI_GROQ);
        assertThat(unicaRigaDiLog()).contains("429").contains(CORPO_DI_ERRORE);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 413, 422})
    void groqRifiutaLaRichiesta_alClientRisponde400(int stato) {
        groq.rispondi(stato, CORPO_DI_ERRORE);

        assertThatThrownBy(() -> service.chat(richiesta(MESSAGGI_VALIDI, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("non valida")
                .hasMessageNotContaining(DETTAGLIO_DI_GROQ);
        assertThat(unicaRigaDiLog()).contains(String.valueOf(stato)).contains(CORPO_DI_ERRORE);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 500, 503})
    void altriErroriDiGroq_alClientRisponde502ConMessaggioNeutro(int stato) {
        // Con il 401 la chiave sul server è sbagliata: il client non lo deve sapere, ma nei log sì
        groq.rispondi(stato, CORPO_DI_ERRORE);

        assertFallisceCon(HttpStatus.BAD_GATEWAY)
                .hasMessageContaining("non è disponibile")
                .hasMessageNotContaining(DETTAGLIO_DI_GROQ)
                .hasMessageNotContaining(String.valueOf(stato))
                .hasMessageNotContaining(CHIAVE);
        assertThat(unicaRigaDiLog()).contains(String.valueOf(stato)).contains(CORPO_DI_ERRORE);
    }

    @Test
    void corpoDiErroreLungo_neiLogSiTroncaA500Caratteri() {
        groq.rispondi(500, "a".repeat(500) + "#".repeat(100));

        assertFallisceCon(HttpStatus.BAD_GATEWAY);
        assertThat(unicaRigaDiLog()).contains("a".repeat(500) + "…").doesNotContain("#");
    }

    @Test
    void groqNonRaggiungibile_risponde502_eLaCausaStaNeiLog() {
        groq.close(); // la porta ora è chiusa: la connessione viene rifiutata

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessageContaining("non raggiungibile");
        assertThat(unicaRigaDiLog()).contains("ConnectException");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<html><body>Bad gateway</body></html>",    // la pagina di errore di un proxy
            "",                                         // nessun corpo
            "   ",
            "{\"choices\":[",                           // JSON a metà
            "null", "[]", "42", "\"ok\""                // JSON valido ma non un oggetto
    })
    void rispostaCheNonEUnOggettoJson_risponde502_eIlCorpoStaNeiLog(String corpo) {
        groq.rispondi(200, corpo);

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessageContaining("risposta non valida");
        assertThat(unicaRigaDiLog()).contains(corpo);
    }

    /* ── Timeout: Groq lento non tiene occupato il thread a tempo indeterminato ── */

    @Test
    @Timeout(10) // senza timeout la chiamata resterebbe appesa a tempo indeterminato e il test cadrebbe qui
    void groqCheNonRisponde_dopoIlTimeoutDiRisposta_risponde502() {
        groq.nonRispondeMai();
        service = nuovoServizio(Duration.ofMillis(300));

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessageContaining("non raggiungibile");
        assertThat(unicaRigaDiLog()).contains("HttpTimeoutException");
    }

    @Test
    @Timeout(10)
    void rispostaCheSiFermaAMeta_dopoIlTimeout_risponde502_eLaCausaStaNeiLog() {
        // Il timeout vale per tutta la risposta, corpo compreso: intestazioni subito, poi silenzio
        groq.fermaLaRispostaAMeta();
        service = nuovoServizio(Duration.ofMillis(300));

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessageContaining("non raggiungibile");
        assertThat(unicaRigaDiLog()).contains("IOException");
    }
}
