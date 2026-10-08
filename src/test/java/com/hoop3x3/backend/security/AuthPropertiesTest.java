package com.hoop3x3.backend.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le proprietà auth.* sono validate all'avvio: con AUTH_REFRESH_GIORNI=0 il cookie usciva con Max-Age=0 e ogni refresh token
 * nasceva già scaduto, senza nessun errore. Ora il server non parte e dice quale proprietà è sbagliata. Il contesto è quello
 * di AuthCookies, che porta con sé @EnableConfigurationProperties(AuthProperties.class) come il server intero.
 */
class AuthPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AuthCookies.class)
            .withBean(LocalValidatorFactoryBean.class);

    // Un contesto che non parte scrive un WARN con tutta l'eccezione: qui è il risultato voluto, quindi si alza la soglia
    private final Logger logDeiContesti = (Logger) LoggerFactory.getLogger(AnnotationConfigApplicationContext.class);

    @BeforeEach
    void nonScrivereIWarnDeiContestiRifiutati() {
        logDeiContesti.setLevel(Level.ERROR);
    }

    @AfterEach
    void ripristinaIlLivelloDelLog() {
        logDeiContesti.setLevel(null);
    }

    @Test
    void senzaProprieta_parteCon30GiorniESecureAcceso() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AuthProperties.class)).isEqualTo(new AuthProperties(30, true));
        });
    }

    // I due estremi dell'intervallo consentito (1 e 365 giorni) sono validi
    @ParameterizedTest
    @ValueSource(strings = {"1", "365"})
    void giorniAgliEstremi_parte(String giorni) {
        runner.withPropertyValues("auth.refresh-giorni=" + giorni).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AuthProperties.class).refreshGiorni()).isEqualTo(Long.parseLong(giorni));
        });
    }

    @Test
    void zeroGiorni_nonParteEDiceQualeProprieta() {
        runner.withPropertyValues("auth.refresh-giorni=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().hasRootCauseInstanceOf(BindValidationException.class);
            StringWriter errore = new StringWriter();
            ctx.getStartupFailure().printStackTrace(new PrintWriter(errore));
            assertThat(errore.toString()).contains("auth.refresh-giorni deve essere almeno 1");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "366"})
    void giorniFuoriDaUnoA365_nonParte(String giorni) {
        runner.withPropertyValues("auth.refresh-giorni=" + giorni).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().hasRootCauseInstanceOf(BindValidationException.class);
        });
    }
}
