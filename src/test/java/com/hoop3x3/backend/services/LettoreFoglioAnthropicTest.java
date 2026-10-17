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
 * Lettura del foglio con Anthropic (Messages API): la forma della richiesta (immagine prima del testo, strumento forzato) e gli
 * errori, gli stessi di Groq. Anthropic è simulato da AnthropicFinto: nessuna chiamata vera, nessuna chiave vera.
 */
class LettoreFoglioAnthropicTest {

    private static final String CHIAVE = "sk-ant-finta-foglio";
    private static final ImmagineFoglio IMMAGINE = new ImmagineFoglio(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3},
            "image/jpeg");
    private static final String BOZZA = """
            {"nome":null,"luogo":"Campo X","data":null,"squadre":[{"nome":"Alfa","giocatori":["Mario","Luca"]}],
             "gironi":[["Alfa","Beta"]],"partite":[{"a":"Alfa","b":"Beta","puntiA":null,"puntiB":12}],
             "dubbi":["Partita Alfa-Beta: il punteggio di Alfa non si legge"]}""";
    private static final String CORPO_DI_ERRORE = "{\"type\":\"error\",\"error\":{\"message\":\"dettaglio interno di Anthropic\"}}";

    private final ObjectMapper mapper = new ObjectMapper();
    private LogCatturato log;
    private AnthropicFinto anthropic;
    private LettoreFoglioAnthropic lettore;

    @BeforeEach
    void conAnthropicFinto() throws IOException {
        log = new LogCatturato(LettoreFoglioAnthropic.class);
        anthropic = new AnthropicFinto();
        lettore = nuovoLettore(CHIAVE, Duration.ofSeconds(5));
    }

    @AfterEach
    void fermaAnthropicFintoEControllaIlLog() {
        anthropic.close();
        log.close();
        assertThat(log.righe()).noneSatisfy(riga -> assertThat(riga).contains(CHIAVE));
        assertThat(log.righe()).noneSatisfy(riga -> assertThat(riga).contains(IMMAGINE.base64()));
    }

    private LettoreFoglioAnthropic nuovoLettore(String chiave, Duration timeoutRisposta) {
        AnthropicProperties proprieta = new AnthropicProperties(new AnthropicProperties.Api(chiave), "claude-di-prova");
        return new LettoreFoglioAnthropic(mapper, proprieta, anthropic.url(), Duration.ofSeconds(5), timeoutRisposta);
    }

    /** Una risposta della Messages API con i blocchi dati in content */
    private static String rispostaCon(String blocchi) {
        return "{\"type\":\"message\",\"stop_reason\":\"tool_use\",\"content\":[" + blocchi + "]}";
    }

    private static String strumento(String input) {
        return "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"foglio_tappa\",\"input\":" + input + "}";
    }

    private AbstractThrowableAssert<?, ? extends Throwable> assertFallisceCon(HttpStatus stato) {
        return assertThatThrownBy(() -> lettore.leggi(IMMAGINE))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(stato));
    }

    private String unicaRigaDiLog() {
        assertThat(log.livelli()).containsExactly(Level.WARN);
        return log.righe().getFirst();
    }

    @Test
    void conLaChiave_eConfigurato_senzaNo() {
        assertThat(lettore.isConfigurato()).isTrue();
        assertThat(nuovoLettore(" ", Duration.ofSeconds(5)).isConfigurato()).isFalse();
    }

    @Test
    void laRichiesta_haChiaveVersioneImmaginePrimaDelTestoEStrumentoForzato() {
        anthropic.rispondi(200, rispostaCon(strumento(BOZZA)));

        lettore.leggi(IMMAGINE);

        assertThat(anthropic.richieste()).singleElement().satisfies(r -> {
            assertThat(r.intestazione("x-api-key")).isEqualTo(CHIAVE);
            assertThat(r.intestazione("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(r.autorizzazione()).as("la chiave va solo in x-api-key").isNull();
            JsonNode inviato = mapper.readTree(r.corpo());
            assertThat(inviato.path("model").asString()).isEqualTo("claude-di-prova");
            assertThat(inviato.path("max_tokens").asInt()).isEqualTo(4000);
            assertThat(inviato.path("messages")).hasSize(1);
            assertThat(inviato.at("/messages/0/role").asString()).isEqualTo("user");
            JsonNode parti = inviato.at("/messages/0/content");
            assertThat(parti).hasSize(2);
            assertThat(parti.at("/0/type").asString()).isEqualTo("image");
            assertThat(parti.at("/0/source/type").asString()).isEqualTo("base64");
            assertThat(parti.at("/0/source/media_type").asString()).isEqualTo("image/jpeg");
            assertThat(parti.at("/0/source/data").asString()).isEqualTo(IMMAGINE.base64());
            assertThat(parti.at("/1/type").asString()).isEqualTo("text");
            assertThat(parti.at("/1/text").asString()).isEqualTo(SchemaFoglio.PROMPT);
            assertThat(inviato.path("tools")).hasSize(1);
            assertThat(inviato.at("/tools/0/name").asString()).isEqualTo("foglio_tappa");
            assertThat(inviato.at("/tools/0/description").asString()).isNotBlank();
            assertThat(inviato.at("/tools/0/input_schema")).isEqualTo(mapper.readTree(SchemaFoglio.SCHEMA));
            assertThat(inviato.at("/tool_choice/type").asString()).isEqualTo("tool");
            assertThat(inviato.at("/tool_choice/name").asString()).isEqualTo("foglio_tappa");
        });
    }

    // Il blocco si cerca per tipo e nome, non per posizione: prima può esserci un blocco di testo o di ragionamento
    @Test
    void unaRispostaConLoStrumento_restituisceLaBozzaDaInput() {
        anthropic.rispondi(200, rispostaCon("{\"type\":\"text\",\"text\":\"Ecco il foglio\"}," + strumento(BOZZA)));

        assertThat(lettore.leggi(IMMAGINE)).isEqualTo(mapper.readTree(BOZZA));
        assertThat(log.righe()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"message\",\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"Non leggo\"}]}",
            "{\"type\":\"message\",\"content\":[]}",
            "{\"type\":\"message\"}",
            "{\"content\":[{\"type\":\"tool_use\",\"name\":\"altro_strumento\",\"input\":{\"squadre\":[],\"partite\":[],\"dubbi\":[]}}]}",
            "{\"content\":[{\"type\":\"tool_use\",\"name\":\"foglio_tappa\",\"input\":{\"squadre\":[]}}]}",
            "<html>Bad gateway</html>"})
    void unaRispostaSenzaLaBozza_risponde502RispostaNonValida(String corpo) {
        anthropic.rispondi(200, corpo);

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.RISPOSTA_NON_VALIDA);
        assertThat(unicaRigaDiLog()).contains("non valida");
    }

    // Lo strumento con una bozza a metà porta i nomi letti: nel log solo lunghezza, chiavi di primo livello e stop_reason
    @Test
    void unaBozzaNonValida_nelLogNonPortaINomiLetti() {
        String input = "{\"squadre\":[{\"nome\":\"Alfa\",\"giocatori\":[\"Mario Rossi\"]}],\"dubbi\":[\"Anna Verdi\"]}";
        anthropic.rispondi(200, "{\"type\":\"message\",\"stop_reason\":\"max_tokens\",\"content\":[" + strumento(input) + "]}");

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.RISPOSTA_NON_VALIDA);
        assertThat(unicaRigaDiLog()).contains(input.length() + " caratteri").contains("[squadre, dubbi]").contains("max_tokens")
                .doesNotContain("Mario Rossi").doesNotContain("Anna Verdi").doesNotContain("Alfa");
    }

    // Una risposta senza lo strumento: il testo del modello (che può citare il foglio) non va nel log
    @Test
    void unaRispostaSoloTesto_nelLogNonPortaIlTesto() {
        anthropic.rispondi(200, rispostaCon("{\"type\":\"text\",\"text\":\"Leggo Mario Rossi e Anna Verdi\"}"));

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.RISPOSTA_NON_VALIDA);
        assertThat(unicaRigaDiLog()).contains("[type, stop_reason, content]").contains("tool_use")
                .doesNotContain("Mario Rossi").doesNotContain("Anna Verdi");
    }

    @Test
    void anthropic429_risponde429ConIlLimiteDelLettore() {
        anthropic.rispondi(429, CORPO_DI_ERRORE);

        assertFallisceCon(HttpStatus.TOO_MANY_REQUESTS).hasMessage(LettoreFoglio.LIMITE_FORNITORE);
        assertThat(unicaRigaDiLog()).contains("429").contains(CORPO_DI_ERRORE);
    }

    // Un 400 (tool_choice forzato rifiutato da un modello che non lo accetta) è configurazione del server: 502
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 413, 422, 500, 529})
    void altriErroriDiAnthropic_risponde502ConUnMessaggioNostro(int stato) {
        anthropic.rispondi(stato, CORPO_DI_ERRORE);

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.FORNITORE_NON_DISPONIBILE);
        assertThat(unicaRigaDiLog()).contains(String.valueOf(stato)).contains(CORPO_DI_ERRORE);
    }

    // Se un errore riportasse la richiesta, l'immagine di Anthropic sta in "data":"<base64>" (senza «base64,»): anche lì si
    // toglie tutto ciò che segue, con o senza spazi intorno ai due punti
    @ParameterizedTest
    @ValueSource(strings = {"\"data\":\"", "\"data\": \"", "\"data\" : \""})
    void unErroreCheRiportaLImmagine_neiLogNonLaScrive(String campo) {
        anthropic.rispondi(400, "{\"error\":{\"message\":\"immagine non valida\",\"source\":{\"type\":\"base64\","
                + campo + IMMAGINE.base64() + "\"}}}");

        assertFallisceCon(HttpStatus.BAD_GATEWAY);
        assertThat(unicaRigaDiLog()).contains("immagine non valida").contains(campo).doesNotContain(IMMAGINE.base64());
    }

    @Test
    void anthropicNonRaggiungibile_risponde502() {
        anthropic.close();

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.FORNITORE_NON_RAGGIUNGIBILE);
    }

    @Test
    @Timeout(10)
    void anthropicCheNonRisponde_dopoIlTimeout_risponde502() {
        anthropic.nonRispondeMai();
        lettore = nuovoLettore(CHIAVE, Duration.ofSeconds(1));

        assertFallisceCon(HttpStatus.BAD_GATEWAY).hasMessage(LettoreFoglio.FORNITORE_NON_RAGGIUNGIBILE);
    }
}
