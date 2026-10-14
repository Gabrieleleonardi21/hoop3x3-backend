package com.hoop3x3.backend.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import static com.hoop3x3.backend.services.AmbitoCoach.REGOLA;
import static com.hoop3x3.backend.services.AmbitoCoach.RIPETIZIONE;
import static com.hoop3x3.backend.services.AmbitoCoach.RISPOSTA_FUORI_CONTESTO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La regola del contesto del Coach: classe pura, senza Spring e senza Groq */
class AmbitoCoachTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ArrayNode messaggi(String json) {
        return (ArrayNode) mapper.readTree(json);
    }

    /** Una risposta di Groq con il content dato (già scritto come JSON: stringa tra virgolette, null, numero) */
    private JsonNode risposta(String content) {
        return mapper.readTree("{\"id\":\"r1\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":" + content + "}}],\"usage\":{\"total_tokens\":12}}");
    }

    /* ── conRegola ── */

    @Test
    void conIlSystemDelClient_laRegolaVaPrimaELaRipetizioneDopo() {
        ArrayNode ricevuti = messaggi("""
                [{"role":"system","content":"Sei Coach AI…"},{"role":"user","content":"ciao"},
                 {"role":"assistant","content":null,"tool_calls":[{"id":"c1"}]}]""");

        ArrayNode inviati = AmbitoCoach.conRegola(ricevuti);

        assertThat(inviati).hasSize(3);
        assertThat(inviati.get(0).path("role").asString()).isEqualTo("system");
        assertThat(inviati.get(0).path("content").asString())
                .isEqualTo(REGOLA + "\n\n" + "Sei Coach AI…" + "\n\n" + RIPETIZIONE);
        assertThat(inviati.get(1)).isEqualTo(ricevuti.get(1));
        assertThat(inviati.get(2)).isEqualTo(ricevuti.get(2));
    }

    @Test
    void senzaSystem_laRegolaEUnSystemNuovoInTesta() {
        ArrayNode ricevuti = messaggi("[{\"role\":\"user\",\"content\":\"ciao\"}]");

        ArrayNode inviati = AmbitoCoach.conRegola(ricevuti);

        assertThat(inviati).hasSize(2);
        assertThat(inviati.get(0).path("role").asString()).isEqualTo("system");
        assertThat(inviati.get(0).path("content").asString()).isEqualTo(REGOLA);
        assertThat(inviati.get(1)).isEqualTo(ricevuti.get(0));
    }

    @Test
    void lElencoRicevutoNonCambia() {
        String json = "[{\"role\":\"system\",\"content\":\"Sei Coach AI\"},{\"role\":\"user\",\"content\":\"ciao\"}]";
        ArrayNode ricevuti = messaggi(json);

        AmbitoCoach.conRegola(ricevuti);
        AmbitoCoach.conRegola(messaggi("[{\"role\":\"user\",\"content\":\"ciao\"}]"));

        assertThat(ricevuti).isEqualTo(messaggi(json));
    }

    // Un system con content non testuale (il formato OpenAI a parti, null, un numero) lo respinge CoachAiService.valida con 400:
    // se arrivasse qui, la regola andrebbe in un system a parte e quello del client avrebbe l'ultima parola. Meglio un errore
    @ParameterizedTest
    @ValueSource(strings = {"[{\"type\":\"text\",\"text\":\"Sei Coach AI\"}]", "null", "42"})
    void unSystemConContentNonTestuale_eUnErrore(String content) {
        ArrayNode ricevuti = messaggi("[{\"role\":\"system\",\"content\":" + content + "},{\"role\":\"user\",\"content\":\"ciao\"}]");

        assertThatThrownBy(() -> AmbitoCoach.conRegola(ricevuti)).isInstanceOf(IllegalArgumentException.class);
    }

    /* ── filtra ── */

    @ParameterizedTest
    @ValueSource(strings = {
            "FUORI_CONTESTO",
            "  FUORI_CONTESTO.\n",                      // spazi, punto e a capo non contano
            "fuori_contesto",                           // nemmeno le maiuscole
            "**FUORI_CONTESTO**",
            "FUORI_CONTESTO. Posso però dirti che…"     // il marcatore in testa basta
    })
    void ilMarcatore_diventaLaFraseFissa(String content) {
        JsonNode filtrata = AmbitoCoach.filtra(risposta(mapper.writeValueAsString(content)));

        assertThat(filtrata.at("/choices/0/message/content").asString()).isEqualTo(RISPOSTA_FUORI_CONTESTO);
    }

    // Senza tool_calls la risposta finisce con «stop», come nel formato OpenAI: «tool_calls» direbbe al client di cercarli
    @Test
    void conIlMarcatore_iToolCallsSpariscono_eLaRispostaFinisceConStop() {
        JsonNode originale = mapper.readTree("""
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"FUORI_CONTESTO",
                 "tool_calls":[{"id":"c1","type":"function","function":{"name":"crea_lega","arguments":"{}"}}]}}]}""");

        JsonNode filtrata = AmbitoCoach.filtra(originale);

        assertThat(filtrata.at("/choices/0/message/content").asString()).isEqualTo(RISPOSTA_FUORI_CONTESTO);
        assertThat(filtrata.at("/choices/0/message").has("tool_calls")).isFalse();
        assertThat(filtrata.at("/choices/0/finish_reason").asString()).isEqualTo("stop");
        // La risposta ricevuta resta com'era: si modifica una copia
        assertThat(originale.at("/choices/0/message").has("tool_calls")).isTrue();
    }

    @Test
    void dopoLaRiscrittura_gliAltriCampiRestanoComErano() {
        JsonNode originale = risposta("\"FUORI_CONTESTO\"");

        JsonNode filtrata = AmbitoCoach.filtra(originale);

        assertThat(filtrata.at("/choices/0/message/content").asString()).isEqualTo(RISPOSTA_FUORI_CONTESTO);
        assertThat(filtrata.path("id").asString()).isEqualTo("r1");
        assertThat(filtrata.at("/choices/0/finish_reason").asString()).isEqualTo("stop");
        assertThat(filtrata.at("/choices/0/index").asInt()).isZero();
        assertThat(filtrata.at("/choices/0/message/role").asString()).isEqualTo("assistant");
        assertThat(filtrata.at("/usage/total_tokens").asInt()).isEqualTo(12);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"Il marcatore FUORI_CONTESTO è solo una parola\"",   // in mezzo a una frase non è un rifiuto
            "\"FUORI_CONTESTOX\"",                                 // il marcatore deve finire lì
            "\"Il pick and roll si gioca così…\"",
            "null",                                                // solo tool_calls
            "42",                                                  // content non testuale
            "[{\"type\":\"text\",\"text\":\"FUORI_CONTESTO\"}]"
    })
    void senzaIlMarcatoreInTesta_laRispostaPassaIntatta(String content) {
        JsonNode originale = risposta(content);

        assertThat(AmbitoCoach.filtra(originale)).isSameAs(originale);
    }

    // gpt-oss può scrivere il marcatore nel ragionamento e poi rispondere lo stesso: conta solo content
    @Test
    void ilMarcatoreSoloNelReasoning_nonEUnRifiuto() {
        JsonNode originale = mapper.readTree("""
                {"choices":[{"message":{"role":"assistant","reasoning":"FUORI_CONTESTO?","content":"Il 3x3 si gioca a metà campo."}}]}""");

        assertThat(AmbitoCoach.filtra(originale)).isSameAs(originale);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"choices\":[]}", "{\"choices\":null}", "{\"choices\":[{}]}", "{\"choices\":[{\"message\":\"x\"}]}"})
    void unaRispostaSenzaMessaggio_passaIntattaSenzaEccezioni(String json) {
        JsonNode originale = mapper.readTree(json);

        assertThat(AmbitoCoach.filtra(originale)).isSameAs(originale);
    }
}
