package com.hoop3x3.backend.runners;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin iniziale: lo si crea solo con una password di almeno 8 caratteri e diversa da quella dell'esempio.
 * Senza contesto Spring: repository e cifratura sono simulati e i campi @Value si impostano a mano.
 */
class DataSeederTest {

    private static final String EMAIL = "admin@hoop3x3.it";
    private static final String PASSWORD_VALIDA = "una-password-lunga";

    private final UtenteRepository utenteRepository = mock(UtenteRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final DataSeeder seeder = new DataSeeder(utenteRepository, passwordEncoder);

    private final Logger logDelSeeder = (Logger) LoggerFactory.getLogger(DataSeeder.class);
    private final ListAppender<ILoggingEvent> logCatturato = new ListAppender<>();
    private String passwordInUso = "";

    @BeforeEach
    void catturaIlLog() {
        // Le righe di log del seeder si leggono dal test e non passano dalla console: l'output della build resta pulito
        logCatturato.start();
        logDelSeeder.addAppender(logCatturato);
        logDelSeeder.setAdditive(false);
    }

    @AfterEach
    void rilasciaIlLogEControllaCheNonContengaLaPassword() {
        logDelSeeder.detachAppender(logCatturato);
        logDelSeeder.setAdditive(true);
        // Qualunque cosa sia successa nel test, la password dell'admin non deve comparire in nessuna riga di log
        if (!passwordInUso.isBlank()) {
            assertThat(logCatturato.list).noneSatisfy(riga -> assertThat(riga.getFormattedMessage()).contains(passwordInUso));
        }
    }

    @Test
    void passwordValida_creaLAdminConEmailNormalizzataEPasswordCifrata() {
        when(passwordEncoder.encode(PASSWORD_VALIDA)).thenReturn("hash-bcrypt");
        conCredenziali("  Admin@Hoop3x3.IT ", PASSWORD_VALIDA);

        seeder.run();

        Utente creato = utenteSalvato();
        assertThat(creato.getEmail()).isEqualTo(EMAIL);
        assertThat(creato.getPassword()).isEqualTo("hash-bcrypt");
        assertThat(creato.getRuolo()).isEqualTo(Ruolo.ADMIN);
        assertThat(logCatturato.list).isEmpty();
    }

    // Il confine esatto: 8 caratteri vanno bene
    @Test
    void passwordDiOttoCaratteri_creaLAdmin() {
        conCredenziali(EMAIL, "abcd1234");

        seeder.run();

        assertThat(utenteSalvato().getRuolo()).isEqualTo(Ruolo.ADMIN);
    }

    // Sette caratteri, e la password dell'esempio (che di caratteri ne ha otto): l'admin non nasce e un avviso dice perché
    @ParameterizedTest
    @ValueSource(strings = {"abcd123", "admin123"})
    void passwordDebole_nonCreaLAdminEAvvisaNelLog(String password) {
        conCredenziali(EMAIL, password);

        seeder.run();

        verify(utenteRepository, never()).save(any());
        assertThat(logCatturato.list).singleElement().satisfies(riga -> {
            assertThat(riga.getLevel()).isEqualTo(Level.WARN);
            assertThat(riga.getFormattedMessage()).contains("ADMIN_PASSWORD");
        });
    }

    // Senza email o senza password il seeder è spento, come prima: nessun admin e nessun avviso
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {"'';" + PASSWORD_VALIDA, EMAIL + ";''", "'   ';'   '"})
    void senzaCredenziali_nonFaNulla(String email, String password) {
        conCredenziali(email, password);

        seeder.run();

        verify(utenteRepository, never()).save(any());
        assertThat(logCatturato.list).isEmpty();
    }

    // Una password debole non conta se l'admin c'è già: non viene usata, quindi niente avviso a ogni riavvio
    @ParameterizedTest
    @ValueSource(strings = {PASSWORD_VALIDA, "admin123"})
    void adminGiaPresente_nonNeCreaUnAltroENonAvvisa(String password) {
        when(utenteRepository.existsByEmail(EMAIL)).thenReturn(true);
        conCredenziali(EMAIL, password);

        seeder.run();

        verify(utenteRepository, never()).save(any());
        assertThat(logCatturato.list).isEmpty();
    }

    /** Imposta i valori che Spring leggerebbe da ADMIN_EMAIL e ADMIN_PASSWORD */
    private void conCredenziali(String email, String password) {
        ReflectionTestUtils.setField(seeder, "adminEmail", email);
        ReflectionTestUtils.setField(seeder, "adminPassword", password);
        passwordInUso = password;
    }

    /** L'unico utente salvato dal seeder */
    private Utente utenteSalvato() {
        ArgumentCaptor<Utente> salvato = ArgumentCaptor.forClass(Utente.class);
        verify(utenteRepository).save(salvato.capture());
        return salvato.getValue();
    }
}
