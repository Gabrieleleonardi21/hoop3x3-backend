package com.hoop3x3.backend.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le proprietà limite.*: i valori di produzione sono quelli del piano e stanno in application.properties, il profilo di
 * test li alza (gli IT fanno più accessi insieme dallo stesso indirizzo), un valore senza senso ferma l'avvio.
 */
class LimiteRichiestePropertiesTest {

    /** Sotto questa soglia un IT potrebbe ricevere un 429: RefreshTokenIT fa 8 accessi insieme per ogni test */
    private static final int ALTI = 10_000;

    @Configuration
    @EnableConfigurationProperties(LimiteRichiesteProperties.class)
    static class Config {}

    // Con l'inizializzatore il contesto legge application.properties come il server (e, col profilo «test», anche
    // application-test.properties)
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withBean(LocalValidatorFactoryBean.class)
            .withInitializer(new ConfigDataApplicationContextInitializer());

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

    // I valori sono scritti in application.properties, dove chi pubblica il server li trova e li cambia: non basta che
    // coincidano con quelli predefiniti nel codice
    @Test
    void iValoriDiProduzioneSonoQuelliDelPiano() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getEnvironment().getProperty("limite.auth-al-minuto")).isEqualTo("10");
            assertThat(ctx.getEnvironment().getProperty("limite.coach-al-minuto")).isEqualTo("20");
            assertThat(ctx.getEnvironment().getProperty("limite.coach-al-giorno")).isEqualTo("300");
            assertThat(ctx.getBean(LimiteRichiesteProperties.class)).isEqualTo(new LimiteRichiesteProperties(10, 20, 300));
        });
    }

    @Test
    void ilProfiloDiTestAlzaILimiti_gliITNonRicevono429() {
        runner.withPropertyValues("spring.profiles.active=test").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LimiteRichiesteProperties limiti = ctx.getBean(LimiteRichiesteProperties.class);
            assertThat(limiti.authAlMinuto()).isGreaterThanOrEqualTo(ALTI);
            assertThat(limiti.coachAlMinuto()).isGreaterThanOrEqualTo(ALTI);
            assertThat(limiti.coachAlGiorno()).isGreaterThanOrEqualTo(ALTI);
        });
    }

    // Senza application.properties (un contesto che non lo carica) valgono comunque i valori di produzione
    @Test
    void senzaProprieta_valgonoIValoriDiProduzione() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).withBean(LocalValidatorFactoryBean.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(LimiteRichiesteProperties.class)).isEqualTo(new LimiteRichiesteProperties(10, 20, 300));
        });
    }

    // Con 0 ogni richiesta verrebbe respinta e l'app resterebbe chiusa a tutti, anche se chi l'ha scritto voleva «nessun
    // limite»: meglio che il server non parta e dica quale proprietà è sbagliata
    @ParameterizedTest
    @CsvSource({
            "limite.auth-al-minuto, 0",
            "limite.auth-al-minuto, -1",
            "limite.coach-al-minuto, 0",
            "limite.coach-al-minuto, -1",
            "limite.coach-al-giorno, 0",
            "limite.coach-al-giorno, -1"
    })
    void unLimiteDaZeroONegativo_ilServerNonParte(String proprieta, String valore) {
        runner.withPropertyValues(proprieta + "=" + valore).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().hasRootCauseInstanceOf(BindValidationException.class);
            assertThat(testoDellErrore(ctx)).contains(proprieta + " deve essere almeno 1");
        });
    }

    /** Tutto quello che l'errore di avvio mostra: messaggi e cause (da qui Spring Boot ricava anche il suo report) */
    private static String testoDellErrore(AssertableApplicationContext ctx) {
        StringWriter errore = new StringWriter();
        ctx.getStartupFailure().printStackTrace(new PrintWriter(errore));
        return errore.toString();
    }
}
