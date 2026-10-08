package com.hoop3x3.backend.runners;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;
import com.hoop3x3.backend.entities.Lega;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.SeedEseguito;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import com.hoop3x3.backend.repositories.LegaRepository;
import com.hoop3x3.backend.repositories.SeedEseguitoRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.ArchivioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le condizioni che fermano il seed demo, e la riga di log che dice quale; il segno che il seed è stato eseguito. Senza
 * contesto Spring e senza database: repository e servizio sono simulati, le proprietà (SeedProperties) si passano al costruttore. Il seed con il
 * database vero lo prova DemoSeederIT.
 */
class DemoSeederTest {

    /** Il nome della lega demo nel file dei dati di prova: è da lei che i database seminati prima del segno si riconoscono */
    private static final String NOME_LEGA_DEMO = "Estathé 3x3 Italia Streetbasket Circuit 2025";

    private final UtenteRepository utenti = mock(UtenteRepository.class);
    private final AnagrafeGiocatoreRepository giocatori = mock(AnagrafeGiocatoreRepository.class);
    private final AnagrafeSquadraRepository squadre = mock(AnagrafeSquadraRepository.class);
    private final LegaRepository leghe = mock(LegaRepository.class);
    private final SeedEseguitoRepository seedEseguiti = mock(SeedEseguitoRepository.class);
    private final ArchivioService archivioService = mock(ArchivioService.class);
    private DemoSeeder seeder = nuovoSeeder(false, "");

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

    // ADMIN_EMAIL già di un utente normale: DataSeeder avvisa e non lo promuove, e i dati di prova non devono finire intestati a lui
    @Test
    void seedAccesoConEmailDiUnUtenteNormale_nonFaNienteEAvvisaNelLog() throws Exception {
        configura(true, "admin@hoop3x3.it");
        Utente normale = new Utente("admin@hoop3x3.it", "hash", "Mario", Ruolo.USER);
        when(utenti.findByEmail("admin@hoop3x3.it")).thenReturn(Optional.of(normale));

        seeder.run();

        assertNessunaScrittura();
        assertSingolaRiga(Level.WARN, "è di un utente con ruolo USER");
    }

    /* ── Il segno del seed eseguito ── */

    // Dopo l'eliminazione della lega demo niente nei dati dice più che il seed è stato fatto: lo dice il segno (SeedEseguito)
    @Test
    void seedGiaEseguito_nonInserisceNienteEDiceIlMotivoConUnaRigaInfo() throws Exception {
        adminTrovato();
        when(seedEseguiti.existsById("demo")).thenReturn(true);

        seeder.run();

        assertNessunaScrittura();
        verify(seedEseguiti, never()).save(any());
        assertSingolaRiga(Level.INFO, "già eseguito");
    }

    // Un database seminato prima del segno ha ancora la lega demo dell'admin (gli id delle tappe sono casuali e non dicono
    // niente): il seed non riparte e il segno si scrive adesso
    @Test
    void databaseSeminatoPrimaDelSegno_nonInserisceNienteEScriveIlSegno() throws Exception {
        Utente admin = adminTrovato();
        when(leghe.existsByOwnerAndNome(admin, NOME_LEGA_DEMO)).thenReturn(true);

        seeder.run();

        verifyNoInteractions(giocatori, squadre, archivioService);
        verify(leghe, never()).save(any());
        assertSegnoScritto();
        assertSingolaRiga(Level.INFO, "segno scritto");
    }

    // Gli id delle tappe demo erano derivati dal file, quindi prevedibili: chiunque poteva creare una tappa con quell'id e il seed
    // non partiva più (la chiave primaria era già occupata). Ora sono casuali: due seed danno id diversi
    @Test
    void gliIdDelleTappeDemoSonoCasuali() throws Exception {
        adminTrovato();
        when(giocatori.save(any(AnagrafeGiocatore.class))).thenAnswer(conIdDelDatabase());
        when(squadre.save(any(AnagrafeSquadra.class))).thenAnswer(conIdDelDatabase());
        when(leghe.save(any(Lega.class))).thenAnswer(chiamata -> chiamata.getArgument(0));

        seeder.run();
        seeder.run(); // il segno è simulato: il secondo avvio risemina

        ArgumentCaptor<Lega> leghe = ArgumentCaptor.forClass(Lega.class);
        verify(this.leghe, times(2)).save(leghe.capture());
        UUID primaVolta = leghe.getAllValues().get(0).getTappe().getFirst().getId();
        UUID secondaVolta = leghe.getAllValues().get(1).getTappe().getFirst().getId();
        assertThat(primaVolta).isNotEqualTo(secondaVolta);
    }

    // Un seed che fallisce non deve fermare l'avvio del server: run() non lancia, il segno non si scrive (il prossimo avvio
    // riprova) e nei log c'è un avviso con la causa
    @Test
    void seIlSeedFallisce_runNonLanciaEAvvisaNelLog() {
        adminTrovato();
        when(giocatori.save(any(AnagrafeGiocatore.class))).thenThrow(new IllegalStateException("colonna mancante"));

        assertThatCode(() -> seeder.run()).doesNotThrowAnyException();

        verify(seedEseguiti, never()).save(any());
        assertThat(logCatturato.list).singleElement().satisfies(riga -> {
            assertThat(riga.getLevel()).isEqualTo(Level.WARN);
            assertThat(riga.getFormattedMessage()).startsWith("Seed demo non riuscito");
            assertThat(riga.getThrowableProxy().getMessage()).isEqualTo("colonna mancante");
        });
    }

    // Alla fine del seed il segno si scrive, dopo i dati e l'archivio: la transazione del seed lo annulla se qualcosa va male
    @Test
    void seedCompletato_scriveIlSegnoAllaFine() throws Exception {
        adminTrovato();
        when(giocatori.save(any(AnagrafeGiocatore.class))).thenAnswer(conIdDelDatabase());
        when(squadre.save(any(AnagrafeSquadra.class))).thenAnswer(conIdDelDatabase());
        when(leghe.save(any(Lega.class))).thenAnswer(chiamata -> chiamata.getArgument(0));

        seeder.run();

        InOrder ordine = inOrder(leghe, archivioService, seedEseguiti);
        ordine.verify(leghe).save(any(Lega.class));
        ordine.verify(archivioService, times(4)).pubblica(any(Utente.class), any(UUID.class));
        ordine.verify(seedEseguiti).save(any(SeedEseguito.class));
        assertSegnoScritto();
    }

    /* ── Aiuti ── */

    /** Il seeder come lo crea Spring con SEED_DEMO e ADMIN_EMAIL */
    private DemoSeeder nuovoSeeder(boolean abilitato, String adminEmail) {
        // Il gestore delle transazioni è simulato: la transazione del seed qui non c'è, la prova DemoSeederIT
        return new DemoSeeder(utenti, giocatori, squadre, leghe, seedEseguiti, archivioService, JsonMapper.builder().build(),
                mock(PlatformTransactionManager.class), new SeedProperties(abilitato, new SeedProperties.Admin(adminEmail, "")));
    }

    private void configura(boolean abilitato, String adminEmail) {
        seeder = nuovoSeeder(abilitato, adminEmail);
    }

    /** SEED_DEMO acceso e un admin che si trova: il seed arriva ai controlli sul segno */
    private Utente adminTrovato() {
        configura(true, "admin@hoop3x3.it");
        Utente admin = new Utente("admin@hoop3x3.it", "hash", "Admin", Ruolo.ADMIN);
        when(utenti.findByEmail("admin@hoop3x3.it")).thenReturn(Optional.of(admin));
        return admin;
    }

    /** Un repository vero restituisce l'entity salvata con l'id che le ha dato il database (il campo non ha un setter) */
    private static <T> Answer<T> conIdDelDatabase() {
        return chiamata -> {
            T entity = chiamata.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
            return entity;
        };
    }

    /** Il segno scritto è uno solo: si chiama «demo» e ha la data */
    private void assertSegnoScritto() {
        ArgumentCaptor<SeedEseguito> segno = ArgumentCaptor.forClass(SeedEseguito.class);
        verify(seedEseguiti).save(segno.capture());
        assertThat(segno.getValue().getNome()).isEqualTo("demo");
        assertThat(segno.getValue().getEseguitoIl()).isNotNull();
    }

    /** Il seeder non ha inserito né cancellato niente: nessun repository di dati demo è stato toccato */
    private void assertNessunaScrittura() {
        verifyNoInteractions(giocatori, squadre, leghe, archivioService);
    }

    /** Il seeder ha scritto una riga sola, di quel livello, e il suo messaggio contiene il motivo */
    private void assertSingolaRiga(Level livello, String motivo) {
        assertThat(logCatturato.list).singleElement().satisfies(riga -> {
            assertThat(riga.getLevel()).isEqualTo(livello);
            assertThat(riga.getFormattedMessage()).startsWith("Seed demo saltato: ").contains(motivo);
        });
    }
}
