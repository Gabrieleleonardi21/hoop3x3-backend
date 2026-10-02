package com.hoop3x3.backend.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/** Il server non deve partire con un secret JWT debole o lasciato al valore d'esempio. */
class JwtPropertiesTest {

    private static final String SECRET_VALIDO = "0123456789abcdef0123456789abcdef";

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withBean(org.springframework.validation.beanvalidation.LocalValidatorFactoryBean.class);

    // Un contesto che non parte scrive un WARN con tutta l'eccezione: qui è il risultato voluto, quindi si alza la
    // soglia del suo logger per non riempire l'output della build di avvisi
    private final Logger logDeiContesti = (Logger) LoggerFactory.getLogger(AnnotationConfigApplicationContext.class);

    @BeforeEach
    void nonScrivereIWarnDeiContestiRifiutati() {
        logDeiContesti.setLevel(Level.ERROR);
    }

    @AfterEach
    void ripristinaIlLivelloDelLog() {
        logDeiContesti.setLevel(null); // null: torna a ereditare il livello del logger padre
    }

    @Test
    void secretCasualeDi32Caratteri_parte() {
        runner.withPropertyValues("jwt.secret=" + SECRET_VALIDO, "jwt.durata-minuti=30")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(JwtProperties.class).durataMinuti()).isEqualTo(30);
                });
    }

    @Test
    void secretTroppoCorto_nonParte() {
        runner.withPropertyValues("jwt.secret=troppo-corto", "jwt.durata-minuti=30")
                .run(JwtPropertiesTest::assertRifiutato);
    }

    // Il confine esatto: 32 caratteri (SECRET_VALIDO) vanno bene, 31 no
    @Test
    void secretDi31Caratteri_nonParte() {
        runner.withPropertyValues("jwt.secret=" + SECRET_VALIDO.substring(1), "jwt.durata-minuti=30")
                .run(JwtPropertiesTest::assertRifiutato);
    }

    // Spring Boot scrive il «valore rifiutato» di un campo nell'errore di avvio, quindi nei log: se il secret è corto ma
    // vero (la password di un altro servizio incollata per sbaglio, per esempio) non deve comparire in nessuna causa
    @Test
    void secretRifiutato_ilSuoValoreNonCompareNegliErrori() {
        String secretVero = "segreto-vero-ma-corto";
        runner.withPropertyValues("jwt.secret=" + secretVero, "jwt.durata-minuti=30").run(ctx -> {
            assertRifiutato(ctx);
            StringWriter errore = new StringWriter();
            ctx.getStartupFailure().printStackTrace(new PrintWriter(errore));
            assertThat(errore.toString()).contains("deve avere almeno 32 caratteri").doesNotContain(secretVero);
        });
    }

    @Test
    void secretDiEsempio_nonParte() {
        runner.withPropertyValues("jwt.secret=" + JwtProperties.SECRET_DI_ESEMPIO, "jwt.durata-minuti=30")
                .run(JwtPropertiesTest::assertRifiutato);
    }

    @Test
    void secretMancante_nonParte() {
        runner.withPropertyValues("jwt.durata-minuti=30").run(JwtPropertiesTest::assertRifiutato);
    }

    // È lo stato di un env.properties appena copiato dall'esempio, con «JWT_SECRET=» ancora vuoto
    @Test
    void secretVuoto_nonParte() {
        runner.withPropertyValues("jwt.secret=", "jwt.durata-minuti=30").run(JwtPropertiesTest::assertRifiutato);
    }

    @Test
    void durataMancante_vale30Minuti() {
        runner.withPropertyValues("jwt.secret=" + SECRET_VALIDO).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(JwtProperties.class).durataMinuti()).isEqualTo(30);
        });
    }

    // I due estremi dell'intervallo consentito (5 e 1440 minuti) sono validi
    @ParameterizedTest
    @ValueSource(strings = {"5", "1440"})
    void durataAgliEstremi_parte(String minuti) {
        runner.withPropertyValues("jwt.secret=" + SECRET_VALIDO, "jwt.durata-minuti=" + minuti).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(JwtProperties.class).durataMinuti()).isEqualTo(Long.parseLong(minuti));
        });
    }

    // Un minuto fuori dall'intervallo da una parte e dall'altra, più zero e un valore negativo
    @ParameterizedTest
    @ValueSource(strings = {"-1", "0", "4", "1441"})
    void durataFuoriDaCinqueA1440Minuti_nonParte(String minuti) {
        runner.withPropertyValues("jwt.secret=" + SECRET_VALIDO, "jwt.durata-minuti=" + minuti)
                .run(JwtPropertiesTest::assertRifiutato);
    }

    /** Il contesto non parte per un errore di validazione delle proprietà, non per un'eccezione uscita da un controllo */
    private static void assertRifiutato(AssertableApplicationContext ctx) {
        assertThat(ctx).hasFailed();
        assertThat(ctx).getFailure().hasRootCauseInstanceOf(BindValidationException.class);
    }
}
