package com.hoop3x3.backend.runners;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import com.hoop3x3.backend.repositories.LegaRepository;
import com.hoop3x3.backend.repositories.TappaRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.ArchivioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le condizioni che fermano il seed demo, e la riga di log che dice quale. Senza contesto Spring e senza database:
 * repository e servizio sono simulati, i campi @Value si impostano a mano. Il seed con il database vero lo prova DemoSeederIT.
 */
class DemoSeederTest {

    private final UtenteRepository utenti = mock(UtenteRepository.class);
    private final AnagrafeGiocatoreRepository giocatori = mock(AnagrafeGiocatoreRepository.class);
    private final AnagrafeSquadraRepository squadre = mock(AnagrafeSquadraRepository.class);
    private final LegaRepository leghe = mock(LegaRepository.class);
    private final TappaRepository tappe = mock(TappaRepository.class);
    private final ArchivioService archivioService = mock(ArchivioService.class);
    private final DemoSeeder seeder = new DemoSeeder(utenti, giocatori, squadre, leghe, tappe, archivioService,
            JsonMapper.builder().build());

    private final Logger logDelSeeder = (Logger) LoggerFactory.getLogger(DemoSeeder.class);
    private final ListAppender<ILoggingEvent> logCatturato = new ListAppender<>();

    @BeforeEach
    void catturaIlLog() {
        // Le righe di log del seeder si leggono dal test e non passano dalla console: l'output della build resta pulito
        logCatturato.start();
        logDelSeeder.addAppender(logCatturato);
        logDelSeeder.setAdditive(false);
    }

    @AfterEach
    void rilasciaIlLog() {
        logDelSeeder.detachAppender(logCatturato);
        logDelSeeder.setAdditive(true);
    }

    // È la configurazione di tutti gli avvii senza dati di prova: non è un errore, quindi una riga INFO e non un avviso
    @Test
    void seedNonAcceso_nonFaNienteEDiceIlMotivoConUnaRigaInfo() throws Exception {
        configura(false, "admin@hoop3x3.it");

        seeder.run();

        assertNessunaScrittura();
        assertSingolaRiga(Level.INFO, "SEED_DEMO non è true");
    }

    // SEED_DEMO acceso ma nessuna email: i dati si intestano all'admin, che così non si può trovare. Chi ha acceso il seed
    // si aspetta i dati: l'avviso gli dice perché non ci sono
    @Test
    void seedAccesoSenzaEmailDellAdmin_nonFaNienteEAvvisaNelLog() throws Exception {
        configura(true, "  ");

        seeder.run();

        assertNessunaScrittura();
        assertSingolaRiga(Level.WARN, "ADMIN_EMAIL è vuota");
    }

    // Di solito l'admin manca perché DataSeeder non l'ha creato (password debole): la sua riga d'avviso dice il resto
    @Test
    void seedAccesoConAdminNonTrovato_nonFaNienteEAvvisaNelLog() throws Exception {
        configura(true, "Admin@Hoop3x3.it");
        when(utenti.findByEmail("admin@hoop3x3.it")).thenReturn(Optional.empty());

        seeder.run();

        assertNessunaScrittura();
        assertSingolaRiga(Level.WARN, "admin Admin@Hoop3x3.it non trovato");
    }

    /** Imposta i valori che Spring leggerebbe da SEED_DEMO e ADMIN_EMAIL */
    private void configura(boolean abilitato, String adminEmail) {
        ReflectionTestUtils.setField(seeder, "abilitato", abilitato);
        ReflectionTestUtils.setField(seeder, "adminEmail", adminEmail);
    }

    /** Il seeder non ha inserito né cancellato niente: nessun repository di dati demo è stato toccato */
    private void assertNessunaScrittura() {
        verifyNoInteractions(giocatori, squadre, leghe, tappe, archivioService);
    }

    /** Il seeder ha scritto una riga sola, di quel livello, e il suo messaggio contiene il motivo */
    private void assertSingolaRiga(Level livello, String motivo) {
        assertThat(logCatturato.list).singleElement().satisfies(riga -> {
            assertThat(riga.getLevel()).isEqualTo(livello);
            assertThat(riga.getFormattedMessage()).startsWith("Seed demo saltato: ").contains(motivo);
        });
    }
}
