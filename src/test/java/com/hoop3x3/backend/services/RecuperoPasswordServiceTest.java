package com.hoop3x3.backend.services;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.entities.TokenRecuperoPassword;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.repositories.TokenCambioEmailRepository;
import com.hoop3x3.backend.repositories.TokenRecuperoPasswordRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.support.Tempo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.hoop3x3.backend.UtenteDiProva.conId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Il recupero password senza Spring: repository simulati, EmailSender finto che ricorda le email, sessioni simulate. Si
 * verifica la logica (che cosa si salva, che cosa si invalida, quando parte l'email), non il database: quello lo prova
 * RecuperoPasswordIT.
 */
class RecuperoPasswordServiceTest {

    private final UtenteRepository utenti = mock(UtenteRepository.class);
    private final TokenRecuperoPasswordRepository tokens = mock(TokenRecuperoPasswordRepository.class);
    private final RefreshTokenService sessioni = mock(RefreshTokenService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final EmailFinto email = new EmailFinto();
    private final TokenCambioEmailRepository tokenDelCambioEmail = mock(TokenCambioEmailRepository.class);
    private final AppProperties proprieta =
            new AppProperties("https://hoop3x3.onrender.com/", new AppProperties.Brevo(""), new AppProperties.Mail(""));
    // Il profilo vero, con gli stessi repository simulati: la chiusura dopo il cambio password è la sua (un punto solo)
    private final ProfiloService profilo = new ProfiloService(utenti, sessioni, encoder, new UtenteService(utenti, encoder),
            tokenDelCambioEmail, tokens, email, proprieta, mock(PlatformTransactionManager.class));
    // La barra finale si toglie: il link non deve avere «//reimposta-password»
    private final RecuperoPasswordService service = new RecuperoPasswordService(utenti, tokens, profilo, encoder, email, proprieta);

    private final Utente mario = conId("mario@x.it");
    private LogCatturato log;

    @BeforeEach
    void setUp() {
        when(utenti.findByEmail("mario@x.it")).thenReturn(Optional.of(mario));
        when(encoder.encode(anyString())).thenReturn("hash-nuovo");
        when(tokens.segnaUsato(any(), any())).thenReturn(1);
        log = new LogCatturato(RecuperoPasswordService.class);
    }

    @AfterEach
    void rilasciaIlLog() {
        log.close();
    }

    /* ── Richiesta del link ── */

    @Test
    void richiesta_salvaSoloLHashDelTokenConScadenzaA30MinutiEInviaIlLink() {
        service.richiedi("  Mario@X.it ");

        ArgumentCaptor<TokenRecuperoPassword> salvato = ArgumentCaptor.forClass(TokenRecuperoPassword.class);
        verify(tokens).save(salvato.capture());
        assertThat(email.inviate()).hasSize(1);
        EmailFinto.Email inviata = email.inviate().getFirst();
        String token = email.ultimoToken();
        assertThat(token).hasSize(43);                                   // 32 byte in Base64 URL senza padding
        assertThat(inviata.destinatario()).isEqualTo("mario@x.it");
        assertThat(inviata.oggetto()).isEqualTo("Reimposta la password di HOOP 3X3");
        assertThat(inviata.testo()).contains("https://hoop3x3.onrender.com/reimposta-password?token=" + token)
                .contains("30 minuti");
        // La versione HTML (EmailRecuperoPassword): il bottone con il link e il logo dal sito, senza la barra doppia
        assertThat(inviata.html()).contains("<a href=\"https://hoop3x3.onrender.com/reimposta-password?token=" + token + "\"")
                .contains("src=\"https://hoop3x3.onrender.com/logo.png\"");
        assertThat(salvato.getValue().getTokenHash()).isEqualTo(RefreshTokenService.sha256(token)).isNotEqualTo(token);
        assertThat(salvato.getValue().getUtente()).isSameAs(mario);
        assertThat(salvato.getValue().getScadenza())
                .isBetween(Tempo.adesso().plusMinutes(29), Tempo.adesso().plusMinutes(31));
    }

    @Test
    void richiesta_invalidaITokenPrecedentiPrimaDiSalvareIlNuovo() {
        service.richiedi("mario@x.it");

        InOrder ordine = inOrder(tokens);
        ordine.verify(tokens).invalidaDellUtente(eq(mario.getId()), any(LocalDateTime.class));
        ordine.verify(tokens).save(any(TokenRecuperoPassword.class));
    }

    // I token creati più di un giorno fa se ne vanno prima del nuovo: la tabella non cresce senza fine. Quelli più recenti
    // restano, quindi il conteggio dei 15 minuti non cambia
    @Test
    void richiesta_cancellaITokenDellUtenteCreatiPiuDiUnGiornoFa_primaDiSalvareIlNuovo() {
        service.richiedi("mario@x.it");

        ArgumentCaptor<LocalDateTime> limite = ArgumentCaptor.forClass(LocalDateTime.class);
        InOrder ordine = inOrder(tokens);
        ordine.verify(tokens).eliminaCreatiPrima(eq(mario.getId()), limite.capture());
        ordine.verify(tokens).save(any(TokenRecuperoPassword.class));
        assertThat(limite.getValue()).isBetween(Tempo.adesso().minusDays(1).minusMinutes(1), Tempo.adesso().minusDays(1).plusMinutes(1));
    }

    @Test
    void dueRichieste_generanoTokenDiversi() {
        service.richiedi("mario@x.it");
        String primo = email.ultimoToken();
        service.richiedi("mario@x.it");

        assertThat(email.ultimoToken()).isNotEqualTo(primo);
    }

    // Per chi chiama non cambia niente: nessun errore, e il controller risponde con lo stesso messaggio. Dietro, nessun token e
    // nessuna email
    @Test
    void richiestaPerUnEmailNonRegistrata_nonSalvaNienteENonInvia() {
        when(utenti.findByEmail("nessuno@x.it")).thenReturn(Optional.empty());

        service.richiedi("nessuno@x.it");

        verifyNoInteractions(tokens);
        assertThat(email.inviate()).isEmpty();
    }

    @Test
    void allaQuartaRichiestaIn15Minuti_nonSiInviaNienteESiAvvisaNelLog() {
        when(tokens.contaCreatiDopo(eq(mario.getId()), any(LocalDateTime.class))).thenReturn(3L);

        service.richiedi("mario@x.it");

        verify(tokens, never()).save(any());
        verify(tokens, never()).invalidaDellUtente(any(), any());
        assertThat(email.inviate()).isEmpty();
        assertThat(log.righe()).anySatisfy(riga -> assertThat(riga).contains("ma***@x.it"));
    }

    @Test
    void laTerzaRichiestaIn15Minuti_parteAncora_eLaFinestraEDi15Minuti() {
        when(tokens.contaCreatiDopo(eq(mario.getId()), any(LocalDateTime.class))).thenReturn(2L);

        service.richiedi("mario@x.it");

        ArgumentCaptor<LocalDateTime> da = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tokens).contaCreatiDopo(eq(mario.getId()), da.capture());
        assertThat(da.getValue()).isBetween(Tempo.adesso().minusMinutes(16), Tempo.adesso().minusMinutes(14));
        assertThat(email.inviate()).hasSize(1);
    }

    // Nei log mai il token né l'email intera: solo le prime due lettere e il dominio
    @Test
    void ilLogNonContieneIlTokenNeLEmailIntera() {
        service.richiedi("mario@x.it");
        when(utenti.findByEmail("nessuno@x.it")).thenReturn(Optional.empty());
        service.richiedi("nessuno@x.it");

        assertThat(log.righe()).isNotEmpty().noneSatisfy(riga -> assertThat(riga)
                .containsAnyOf(email.ultimoToken(), "mario@x.it", "nessuno@x.it"));
    }

    /* ── Nuova password ── */

    /** Un token salvato per Mario, valido fino a `scadenza`, già usato se `usatoIl` non è null */
    private TokenRecuperoPassword salvato(String token, LocalDateTime scadenza, LocalDateTime usatoIl) {
        TokenRecuperoPassword salvato = new TokenRecuperoPassword(mario, RefreshTokenService.sha256(token), scadenza);
        ReflectionTestUtils.setField(salvato, "usatoIl", usatoIl);
        when(tokens.findByTokenHash(RefreshTokenService.sha256(token))).thenReturn(Optional.of(salvato));
        return salvato;
    }

    @Test
    void reimpostaConUnTokenValido_aggiornaLaPasswordSegnaIlTokenInvalidaGliAltriERevocaLeSessioni() {
        TokenRecuperoPassword token = salvato("buono", Tempo.adesso().plusMinutes(10), null);

        service.reimposta("buono", "nuova-password");

        assertThat(mario.getPassword()).isEqualTo("hash-nuovo");
        verify(encoder).encode("nuova-password");
        verify(utenti).save(mario);
        verify(tokens).segnaUsato(eq(token.getId()), any(LocalDateTime.class));
        // Le stesse chiusure del cambio password dal profilo, nello stesso ordine: sessioni, link di cambio email, link di recupero
        InOrder ordine = inOrder(utenti, sessioni, tokenDelCambioEmail, tokens);
        ordine.verify(utenti).save(mario);
        ordine.verify(sessioni).revocaTutte(mario);
        ordine.verify(tokenDelCambioEmail).invalidaDellUtente(eq(mario.getId()), any(LocalDateTime.class));
        ordine.verify(tokens).invalidaDellUtente(eq(mario.getId()), any(LocalDateTime.class));
        // Nel log l'id dell'utente, mai la password né il token
        assertThat(log.righe()).isNotEmpty().noneSatisfy(riga -> assertThat(riga).containsAnyOf("buono", "nuova-password"));
    }

    @Test
    void reimpostaConUnTokenScaduto_rispondeLinkNonValidoENonCambiaNiente() {
        salvato("scaduto", Tempo.adesso().minusSeconds(1), null);

        assertLinkNonValido("scaduto");
    }

    @Test
    void reimpostaConUnTokenGiaUsato_rispondeLinkNonValidoENonCambiaNiente() {
        salvato("usato", Tempo.adesso().plusMinutes(10), Tempo.adesso().minusMinutes(1));

        assertLinkNonValido("usato");
    }

    @Test
    void reimpostaConUnTokenSconosciuto_rispondeLinkNonValidoENonCambiaNiente() {
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertLinkNonValido("sconosciuto");
    }

    // Due richieste insieme con lo stesso token: lo segna una sola, l'altra trova la riga già usata e non cambia la password
    @Test
    void reimpostaQuandoUnAltraRichiestaHaGiaUsatoIlToken_rispondeLinkNonValido() {
        salvato("conteso", Tempo.adesso().plusMinutes(10), null);
        when(tokens.segnaUsato(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reimposta("conteso", "nuova-password"))
                .isInstanceOf(BadRequestException.class).hasMessage(RecuperoPasswordService.LINK_NON_VALIDO);
        assertThat(mario.getPassword()).isEqualTo("hash");
        verifyNoInteractions(sessioni);
    }

    // Stessa regola della registrazione (UtenteService.controllaPassword): 40 lettere accentate sono 80 byte, oltre BCrypt.
    // Il token resta valido: chi corregge la password lo riusa
    @Test
    void reimpostaConUnaPasswordOltreI72Byte_risponde400SenzaUsareIlToken() {
        salvato("buono", Tempo.adesso().plusMinutes(10), null);

        assertThatThrownBy(() -> service.reimposta("buono", "è".repeat(40)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("72 byte");
        verify(tokens, never()).segnaUsato(any(), any());
        verifyNoInteractions(sessioni, encoder);
        assertThat(mario.getPassword()).isEqualTo("hash");
    }

    // La password dell'esempio pubblico non vale per nessun account, come alla registrazione (UtenteService.controllaPassword).
    // Il token resta valido: chi ne sceglie un'altra riusa lo stesso link
    @Test
    void reimpostaConLaPasswordAdmin123_risponde400SenzaUsareIlToken() {
        salvato("buono", Tempo.adesso().plusMinutes(10), null);

        assertThatThrownBy(() -> service.reimposta("buono", "admin123"))
                .isInstanceOf(BadRequestException.class).hasMessage("Questa password non è consentita: scegline un'altra");
        verify(tokens, never()).segnaUsato(any(), any());
        verifyNoInteractions(sessioni, encoder);
        assertThat(mario.getPassword()).isEqualTo("hash");
    }

    private void assertLinkNonValido(String token) {
        assertThatThrownBy(() -> service.reimposta(token, "nuova-password"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Il link non è valido o è scaduto. Richiedine uno nuovo.");
        verify(tokens, never()).segnaUsato(any(), any());
        verify(utenti, never()).save(any());
        verifyNoInteractions(sessioni, encoder);
        assertThat(mario.getPassword()).isEqualTo("hash");
    }
}
