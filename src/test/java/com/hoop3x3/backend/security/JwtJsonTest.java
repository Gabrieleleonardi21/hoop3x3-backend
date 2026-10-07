package com.hoop3x3.backend.security;

import io.jsonwebtoken.io.DeserializationException;
import io.jsonwebtoken.io.Deserializer;
import io.jsonwebtoken.io.Serializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'adattatore tra JJWT e Jackson 3, da solo. Che i token emessi e letti da JwtTools facciano il giro completo lo prova
 * JwtToolsTest: qui si guarda il JSON scritto e le regole di lettura.
 */
class JwtJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Serializer<Map<String, ?>> serializzatore = JwtJson.serializzatore(mapper);
    private final Deserializer<Map<String, ?>> deserializzatore = JwtJson.deserializzatore(mapper);

    @Test
    void cioCheSiScriveSiRilegge() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "11111111-2222-4333-8444-555555555555");
        claims.put("iat", 1_700_000_000L);
        claims.put("exp", 4_102_444_800L);

        byte[] json = serializzatore.serialize(claims);
        Map<String, ?> letti = deserializzatore.deserialize(json);

        // Oggetto compatto, chiavi nell'ordine d'inserimento e numeri senza virgolette: il formato dei JWT di sempre
        assertThat(new String(json, UTF_8))
                .isEqualTo("{\"sub\":\"11111111-2222-4333-8444-555555555555\",\"iat\":1700000000,\"exp\":4102444800}");
        assertThat(letti.get("sub")).isEqualTo("11111111-2222-4333-8444-555555555555");
        assertThat(((Number) letti.get("iat")).longValue()).isEqualTo(1_700_000_000L);
        assertThat(((Number) letti.get("exp")).longValue()).isEqualTo(4_102_444_800L);
        assertThat(letti).hasSize(3);
    }

    // JJWT scrive l'intestazione e i claims su un suo stream e lo chiude da sé: se lo chiudesse Jackson, la scrittura che
    // segue (il punto, il payload, la firma) andrebbe su uno stream già chiuso
    @Test
    void loStreamDiJjwtNonVieneChiusoDaJackson() throws IOException {
        boolean[] chiuso = {false};
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                chiuso[0] = true;
                super.close();
            }
        };

        serializzatore.serialize(Map.of("alg", "HS256"), out);

        assertThat(chiuso[0]).isFalse();
        assertThat(out.toString(UTF_8)).isEqualTo("{\"alg\":\"HS256\"}");
    }

    @Test
    void unaChiaveRipetutaVieneRifiutata() {
        assertThatThrownBy(() -> deserializzatore.deserialize("{\"sub\":\"a\",\"sub\":\"b\"}".getBytes(UTF_8)))
                .isInstanceOf(DeserializationException.class).hasMessageContaining("Duplicate");
    }

    // Anche una chiave ripetuta dentro un oggetto annidato: i claims non sono solo il primo livello
    @Test
    void unaChiaveRipetutaInUnOggettoAnnidatoVieneRifiutata() {
        assertThatThrownBy(() -> deserializzatore.deserialize("{\"x\":{\"a\":1,\"a\":2}}".getBytes(UTF_8)))
                .isInstanceOf(DeserializationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"sub\":",        // finisce a metà
            "{\"sub\":\"x\",}", // virgola in più
            "{sub:x}",          // senza virgolette
            "[\"x\"]",          // un array, non un oggetto
            "\"x\"",            // una stringa
            ""})                // niente
    void unJsonCheNonEUnOggettoValidoVieneRifiutato(String json) {
        assertThatThrownBy(() -> deserializzatore.deserialize(new StringReader(json)))
                .isInstanceOf(DeserializationException.class);
    }

    // Il rilevamento dei duplicati vale per questo lettore e non cambia il mapper dell'applicazione, che ne serve altri: le
    // risposte dell'API, le tappe, il Coach AI
    @Test
    void ilMapperDellApplicazioneNonCambia() {
        deserializzatore.deserialize("{\"a\":1}".getBytes(UTF_8));

        Map<?, ?> conChiaveRipetuta = mapper.readValue("{\"a\":1,\"a\":2}", Map.class);

        assertThat(conChiaveRipetuta.get("a")).isEqualTo(2); // senza il controllo dei duplicati vince l'ultima
    }
}
