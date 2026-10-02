package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.CoachChatRequestDTO;
import com.hoop3x3.backend.exceptions.UpstreamException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;

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

    private final ObjectMapper mapper = new ObjectMapper();
    private GroqFinto groq;
    private CoachAiService service;

    @BeforeEach
    void conGroqFintoEChiaveConfigurata() throws IOException {
        groq = new GroqFinto();
        service = nuovoServizio(Duration.ofSeconds(5));
    }

    @AfterEach
    void fermaGroqFinto() {
        groq.close();
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
    }

    @Test
    @Timeout(10) // senza timeout la chiamata resterebbe appesa a tempo indeterminato e il test cadrebbe qui
    void groqCheNonRisponde_dopoIlTimeoutDiRisposta_risponde502() {
        groq.nonRispondeMai();
        CoachAiService conTimeoutBreve = nuovoServizio(Duration.ofMillis(300));

        assertThatThrownBy(() -> conTimeoutBreve.chat(richiesta(MESSAGGI_VALIDI, null)))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
