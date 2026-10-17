package com.hoop3x3.backend.services;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo schema della bozza va a Groq con strict: true, che accetta solo schemi chiusi: ogni oggetto con additionalProperties
 * false e tutte le sue proprietà in required. Uno schema che non lo rispetta farebbe rispondere 400 a ogni foto.
 */
class SchemaFoglioTest {

    private final JsonNode schema = new ObjectMapper().readTree(SchemaFoglio.SCHEMA);

    @Test
    void loSchemaEUnOggettoJsonConICampiDellaBozza() {
        assertThat(schema.path("type").asString()).isEqualTo("object");
        assertThat(schema.path("properties").propertyNames())
                .containsExactlyInAnyOrder("nome", "luogo", "data", "squadre", "gironi", "partite", "dubbi");
    }

    @Test
    void ogniOggettoEChiusoEHaTutteLeProprietaInRequired() {
        List<JsonNode> oggetti = new ArrayList<>();
        raccogliOggetti(schema, oggetti);

        assertThat(oggetti).as("la bozza, le squadre e le partite").hasSize(3);
        assertThat(oggetti).allSatisfy(oggetto -> {
            assertThat(oggetto.path("additionalProperties").isBoolean()).isTrue();
            assertThat(oggetto.path("additionalProperties").asBoolean()).isFalse();
            List<String> richieste = new ArrayList<>();
            oggetto.path("required").forEach(nome -> richieste.add(nome.asString()));
            assertThat(richieste).containsExactlyInAnyOrderElementsOf(oggetto.path("properties").propertyNames());
        });
    }

    @Test
    void ilPromptChiedeDiNonInventareEDiSegnalareIDubbi() {
        // Una riga sola, come prompt.txt della prova OCR: le barre a fine riga del text block la continuano
        assertThat(SchemaFoglio.PROMPT).startsWith("Leggi questo foglio").endsWith("numeri interi.").doesNotContain("\n")
                .doesNotContain("  ").contains("non inventare").contains("dubbi");
        assertThat(SchemaFoglio.NOME_STRUMENTO).isEqualTo("foglio_tappa");
    }

    /** Ogni nodo dello schema che descrive un oggetto, a qualunque profondità */
    private static void raccogliOggetti(JsonNode nodo, List<JsonNode> oggetti) {
        if (!nodo.isContainer()) return;
        if (nodo.isObject() && "object".equals(nodo.path("type").asString(""))) oggetti.add(nodo);
        for (JsonNode figlio : nodo) raccogliOggetti(figlio, oggetti);
    }
}
