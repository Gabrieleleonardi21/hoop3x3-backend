package com.hoop3x3.backend;

import com.hoop3x3.backend.runners.SeedProperties;
import com.hoop3x3.backend.security.AuthProperties;
import com.hoop3x3.backend.security.CorsProperties;
import com.hoop3x3.backend.services.GroqProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le proprietà lette in record @ConfigurationProperties (BE-15) con gli stessi valori predefiniti dei @Value che sostituiscono, e
 * le stesse chiavi di application.properties: senza nessuna proprietà il server si comporta come prima.
 */
class ProprietaTest {

    private static <T> T leggi(Map<String, String> valori, String prefisso, Class<T> tipo) {
        return new Binder(new MapConfigurationPropertySource(valori)).bindOrCreate(prefisso, tipo);
    }

    @Test
    void senzaProprietaValgonoIValoriDiPrima() {
        Map<String, String> nessuna = Map.of();
        assertThat(leggi(nessuna, "cors", CorsProperties.class).origins())
                .containsExactly("http://localhost:5173", "http://127.0.0.1:5173");
        AuthProperties auth = leggi(nessuna, "auth", AuthProperties.class);
        assertThat(auth.refreshGiorni()).isEqualTo(30);
        assertThat(auth.cookieSecure()).isFalse();
        SeedProperties seed = leggi(nessuna, "seed", SeedProperties.class);
        assertThat(seed.demo()).isFalse();
        assertThat(seed.admin().email()).isEmpty();
        assertThat(seed.admin().password()).isEmpty();
        GroqProperties groq = leggi(nessuna, "groq", GroqProperties.class);
        assertThat(groq.api().key()).isEmpty();
        assertThat(groq.model()).isEqualTo("openai/gpt-oss-120b");
    }

    @Test
    void leChiaviSonoQuelleDiApplicationProperties() {
        Map<String, String> valori = Map.of(
                "cors.origins", "https://a.it,https://b.it",
                "auth.refresh-giorni", "7", "auth.cookie-secure", "true",
                "seed.demo", "true", "seed.admin.email", "a@prova.it", "seed.admin.password", "Segreta-di-prova-1",
                "groq.api.key", "chiave-di-prova", "groq.model", "modello-di-prova");
        assertThat(leggi(valori, "cors", CorsProperties.class).origins()).containsExactly("https://a.it", "https://b.it");
        assertThat(leggi(valori, "auth", AuthProperties.class)).isEqualTo(new AuthProperties(7, true));
        SeedProperties seed = leggi(valori, "seed", SeedProperties.class);
        assertThat(seed.demo()).isTrue();
        assertThat(seed.admin().email()).isEqualTo("a@prova.it");
        assertThat(seed.admin().password()).isEqualTo("Segreta-di-prova-1");
        GroqProperties groq = leggi(valori, "groq", GroqProperties.class);
        assertThat(groq.api().key()).isEqualTo("chiave-di-prova");
        assertThat(groq.model()).isEqualTo("modello-di-prova");
    }

    // Il toString() automatico di un record scrive tutti i campi: password e chiave non devono finire nei log
    @Test
    void passwordEChiaveNonCompaionoNelToString() {
        assertThat(new SeedProperties(true, new SeedProperties.Admin("a@prova.it", "Segreta-di-prova-1")).toString())
                .doesNotContain("Segreta-di-prova-1");
        assertThat(new GroqProperties(new GroqProperties.Api("chiave-di-prova"), "m").toString())
                .doesNotContain("chiave-di-prova");
    }
}
