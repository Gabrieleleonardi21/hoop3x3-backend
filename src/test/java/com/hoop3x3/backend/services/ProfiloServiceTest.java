package com.hoop3x3.backend.services;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.TokenCambioEmail;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.exceptions.ConflictException;
import com.hoop3x3.backend.exceptions.ForbiddenException;
import com.hoop3x3.backend.exceptions.TroppeRichiesteException;
import com.hoop3x3.backend.repositories.TokenCambioEmailRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.support.Tempo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * Il profilo dell'account senza Spring: repository e sessioni simulati, encoder simulato che riconosce solo la password
 * «vecchia-password». Si verifica la logica (che cosa si controlla, in che ordine, che cosa si salva); il database e la
 * catena vera li prova ProfiloIT.
 */
class ProfiloServiceTest {

    private final UtenteRepository utenti = mock(UtenteRepository.class);
    private final RefreshTokenService sessioni = mock(RefreshTokenService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final TokenCambioEmailRepository tokens = mock(TokenCambioEmailRepository.class);
    private final EmailFinto email = new EmailFinto();
    // La barra finale si toglie: il link non deve avere «//conferma-email»
    private final ProfiloService service = new ProfiloService(utenti, sessioni, encoder, new UtenteService(utenti, encoder),
            tokens, email, new AppProperties("https://hoop3x3.onrender.com/", new AppProperties.Brevo(""), new AppProperties.Mail("")));

    private final Utente mario = conId("mario@x.it");
    private LogCatturato log;

    @BeforeEach
    void setUp() {
        // L'hash di mario è «hash» (UtenteDiProva): solo «vecchia-password» lo verifica
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        when(encoder.matches("vecchia-password", "hash")).thenReturn(true);
        when(encoder.encode(anyString())).thenReturn("hash-nuovo");
        when(utenti.save(any(Utente.class))).thenAnswer(chiamata -> chiamata.getArgument(0));
        when(tokens.segnaUsato(any(), any())).thenReturn(1);
        log = new LogCatturato(ProfiloService.class);
    }

    @AfterEach
    void rilasciaIlLog() {
        log.close();
    }

    /* ── Nome ── */

    @Test
    void cambiaNome_salvaIlNomeSenzaSpaziAiLati_eLoRestituisce() {
        Utente salvato = service.cambiaNome(mario, "  Mario Rossi ");

        assertThat(salvato.getNome()).isEqualTo("Mario Rossi");
        verify(utenti).save(mario);
        // Nel log l'id, mai il nome: lo scrive l'utente
        assertThat(log.righe()).containsExactly("Nome utente cambiato: utente " + mario.getId());
    }

    /* ── Password ── */

    @Test
    void cambiaPassword_conLAttualeSbagliata_400_eNonCambiaNiente() {
        assertThatThrownBy(() -> service.cambiaPassword(mario, "sbagliata", "nuova-password"))
                .isInstanceOf(BadRequestException.class).hasMessage(ProfiloService.PASSWORD_ATTUALE_ERRATA)
                .hasMessage("La password attuale non è corretta");

        verify(utenti, never()).save(any());
        verifyNoInteractions(sessioni);
        assertThat(mario.getPassword()).isEqualTo("hash");
    }

    @Test
    void cambiaPassword_conLaNuovaUgualeAllAttuale_400() {
        assertThatThrownBy(() -> service.cambiaPassword(mario, "vecchia-password", "vecchia-password"))
                .isInstanceOf(BadRequestException.class).hasMessage(ProfiloService.PASSWORD_UGUALE)
                .hasMessage("La nuova password è uguale a quella attuale");

        verify(utenti, never()).save(any());
        verifyNoInteractions(sessioni);
    }

    // Le regole della registrazione (UtenteService.controllaPassword): la password dell'esempio pubblico e i 72 byte di BCrypt
    @Test
    void cambiaPassword_conAdmin123_400ConIlMessaggioDellaRegistrazione() {
        assertThatThrownBy(() -> service.cambiaPassword(mario, "vecchia-password", "admin123"))
                .isInstanceOf(BadRequestException.class).hasMessage("Questa password non è consentita: scegline un'altra");

        verify(utenti, never()).save(any());
        verifyNoInteractions(sessioni);
    }

    @Test
    void cambiaPassword_oltreI72Byte_400ConIlMessaggioDeiByte() {
        assertThatThrownBy(() -> service.cambiaPassword(mario, "vecchia-password", "è".repeat(40)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("72 byte");

        verify(utenti, never()).save(any());
        verify(encoder, never()).encode(anyString());
        verifyNoInteractions(sessioni);
    }

    @Test
    void cambiaPassword_salvaLHashDellaNuova_ePoiChiudeTutteLeSessioni() {
        service.cambiaPassword(mario, "vecchia-password", "nuova-password");

        assertThat(mario.getPassword()).isEqualTo("hash-nuovo");
        InOrder ordine = inOrder(encoder, utenti, sessioni);
        ordine.verify(encoder).encode("nuova-password");
        ordine.verify(utenti).save(mario);
        ordine.verify(sessioni).revocaTutte(mario);
        // Nel log l'id, mai le password
        assertThat(log.righe()).containsExactly("Password cambiata dal profilo: utente " + mario.getId());
    }

    /* ── Richiesta del cambio email ── */

    // L'ADMIN nasce da ADMIN_EMAIL (DataSeeder): con un'email cambiata dall'app, al riavvio ne nascerebbe un altro. Il 403
    // viene prima di ogni altro controllo, password compresa
    @Test
    void richiestaDellAdmin_403PrimaDiOgniAltroControllo() {
        Utente admin = conId("admin@x.it", Ruolo.ADMIN);

        assertThatThrownBy(() -> service.richiediCambioEmail(admin, "nuova@x.it", "sbagliata"))
                .isInstanceOf(ForbiddenException.class).hasMessage(ProfiloService.ADMIN_DALLA_CONFIGURAZIONE)
                .hasMessage("L'account ADMIN si gestisce dalla configurazione del server (ADMIN_EMAIL)");

        verifyNoInteractions(tokens, encoder);
        assertThat(email.inviate()).isEmpty();
    }

    @Test
    void richiestaConLaPasswordSbagliata_400_eNonParteNiente() {
        assertThatThrownBy(() -> service.richiediCambioEmail(mario, "nuova@x.it", "sbagliata"))
                .isInstanceOf(BadRequestException.class).hasMessage(ProfiloService.PASSWORD_ATTUALE_ERRATA);

        verifyNoInteractions(tokens);
        assertThat(email.inviate()).isEmpty();
    }

    // Si confrontano le email normalizzate: «Mario@X.it» è l'indirizzo che l'account ha già
    @Test
    void richiestaPerLEmailCheLAccountHaGia_400() {
        assertThatThrownBy(() -> service.richiediCambioEmail(mario, "  Mario@X.it ", "vecchia-password"))
                .isInstanceOf(BadRequestException.class).hasMessage(ProfiloService.EMAIL_UGUALE)
                .hasMessage("È già l'email del tuo account");

        verifyNoInteractions(tokens);
        assertThat(email.inviate()).isEmpty();
    }

    // Lo stesso testo della registrazione (UtenteService.controllaEmailLibera)
    @Test
    void richiestaPerUnEmailGiaRegistrata_409ConIlTestoDellaRegistrazione() {
        when(utenti.existsByEmail("luigi@x.it")).thenReturn(true);

        assertThatThrownBy(() -> service.richiediCambioEmail(mario, "Luigi@x.it", "vecchia-password"))
                .isInstanceOf(ConflictException.class).hasMessage("Email luigi@x.it già registrata");

        verify(tokens, never()).save(any());
        assertThat(email.inviate()).isEmpty();
    }

    @Test
    void allaQuartaRichiestaIn15Minuti_429ENonParteNiente() {
        when(tokens.contaCreatiDopo(eq(mario.getId()), any(LocalDateTime.class))).thenReturn(3L);

        assertThatThrownBy(() -> service.richiediCambioEmail(mario, "nuova@x.it", "vecchia-password"))
                .isInstanceOf(TroppeRichiesteException.class)
                .hasMessage("Hai già richiesto 3 link in 15 minuti: riprova tra 15 minuti")
                .satisfies(e -> assertThat(((TroppeRichiesteException) e).getSecondiAttesa()).isEqualTo(900));

        ArgumentCaptor<LocalDateTime> da = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tokens).contaCreatiDopo(eq(mario.getId()), da.capture());
        assertThat(da.getValue()).isBetween(Tempo.adesso().minusMinutes(16), Tempo.adesso().minusMinutes(14));
        verify(tokens, never()).save(any());
        assertThat(email.inviate()).isEmpty();
    }

    // Il link va al NUOVO indirizzo (D3); l'email dell'account cambia solo alla conferma
    @Test
    void richiesta_salvaSoloLHashConLEmailNuovaNormalizzata_eMandaIlLinkAlNuovoIndirizzo() {
        service.richiediCambioEmail(mario, "  Mario.Nuovo@Y.it ", "vecchia-password");

        ArgumentCaptor<LocalDateTime> limite = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<TokenCambioEmail> salvato = ArgumentCaptor.forClass(TokenCambioEmail.class);
        InOrder ordine = inOrder(tokens);
        ordine.verify(tokens).eliminaCreatiPrima(eq(mario.getId()), limite.capture());
        ordine.verify(tokens).invalidaDellUtente(eq(mario.getId()), any(LocalDateTime.class));
        ordine.verify(tokens).save(salvato.capture());
        assertThat(limite.getValue()).isBetween(Tempo.adesso().minusDays(1).minusMinutes(1), Tempo.adesso().minusDays(1).plusMinutes(1));

        String token = email.ultimoToken();
        assertThat(email.inviate()).singleElement().satisfies(inviata -> {
            assertThat(inviata.destinatario()).isEqualTo("mario.nuovo@y.it");
            assertThat(inviata.oggetto()).isEqualTo("Conferma il nuovo indirizzo email di HOOP 3X3");
            assertThat(inviata.testo()).contains("https://hoop3x3.onrender.com/conferma-email?token=" + token);
            assertThat(inviata.html()).contains("<a href=\"https://hoop3x3.onrender.com/conferma-email?token=" + token + "\"");
        });
        assertThat(salvato.getValue().getEmailNuova()).isEqualTo("mario.nuovo@y.it");
        assertThat(salvato.getValue().getUtente()).isSameAs(mario);
        assertThat(salvato.getValue().getTokenHash()).isEqualTo(RefreshTokenService.sha256(token)).isNotEqualTo(token);
        assertThat(salvato.getValue().getScadenza()).isBetween(Tempo.adesso().plusMinutes(29), Tempo.adesso().plusMinutes(31));
        assertThat(mario.getEmail()).isEqualTo("mario@x.it");
        // Nel log l'id e l'indirizzo nuovo mascherato: mai le email intere né il token
        assertThat(log.righe()).containsExactly("Cambio email richiesto: utente " + mario.getId() + ", nuovo indirizzo ma***@y.it");
        verifyNoInteractions(sessioni);
    }

    /* ── Conferma ── */

    /** Un token salvato per Mario verso `emailNuova`, valido fino a `scadenza`, già usato se `usatoIl` non è null */
    private TokenCambioEmail salvato(String token, String emailNuova, LocalDateTime scadenza, LocalDateTime usatoIl) {
        TokenCambioEmail salvato = new TokenCambioEmail(mario, emailNuova, RefreshTokenService.sha256(token), scadenza);
        ReflectionTestUtils.setField(salvato, "usatoIl", usatoIl);
        when(tokens.findByTokenHash(RefreshTokenService.sha256(token))).thenReturn(Optional.of(salvato));
        return salvato;
    }

    // Le sessioni restano: il JWT identifica l'utente per id, e l'email nuova non le rende meno sue
    @Test
    void confermaConUnTokenValido_cambiaLEmail_segnaIlToken_einvalidaGliAltri_senzaToccareLeSessioni() {
        TokenCambioEmail token = salvato("buono", "mario.nuovo@y.it", Tempo.adesso().plusMinutes(10), null);

        Utente aggiornato = service.confermaEmail("buono");

        assertThat(aggiornato.getEmail()).isEqualTo("mario.nuovo@y.it");
        verify(tokens).segnaUsato(eq(token.getId()), any(LocalDateTime.class));
        verify(tokens).invalidaDellUtente(eq(mario.getId()), any(LocalDateTime.class));
        verify(utenti).save(mario);
        verifyNoInteractions(sessioni);
        assertThat(log.righe()).containsExactly("Email cambiata: utente " + mario.getId());
    }

    @Test
    void confermaConUnTokenSconosciuto_usato_oScaduto_400LinkNonValido() {
        salvato("usato", "a@y.it", Tempo.adesso().plusMinutes(10), Tempo.adesso().minusMinutes(1));
        salvato("scaduto", "b@y.it", Tempo.adesso().minusSeconds(1), null);
        when(tokens.findByTokenHash(RefreshTokenService.sha256("sconosciuto"))).thenReturn(Optional.empty());

        for (String token : new String[]{"usato", "scaduto", "sconosciuto"}) {
            assertThatThrownBy(() -> service.confermaEmail(token)).as(token)
                    .isInstanceOf(BadRequestException.class).hasMessage(RecuperoPasswordService.LINK_NON_VALIDO);
        }
        verify(tokens, never()).segnaUsato(any(), any());
        verify(utenti, never()).save(any());
        assertThat(mario.getEmail()).isEqualTo("mario@x.it");
    }

    // Due richieste insieme con lo stesso link: lo segna una sola, l'altra non cambia l'email
    @Test
    void confermaQuandoUnAltraRichiestaHaGiaUsatoIlToken_400() {
        salvato("conteso", "mario.nuovo@y.it", Tempo.adesso().plusMinutes(10), null);
        when(tokens.segnaUsato(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confermaEmail("conteso"))
                .isInstanceOf(BadRequestException.class).hasMessage(RecuperoPasswordService.LINK_NON_VALIDO);
        verify(utenti, never()).save(any());
        assertThat(mario.getEmail()).isEqualTo("mario@x.it");
    }

    // Un altro si è registrato con quell'indirizzo dopo la richiesta: 409, l'email resta, e il token è comunque consumato
    @Test
    void confermaDiUnIndirizzoNelFrattempoRegistrato_409_eLEmailResta_maIlTokenEConsumato() {
        TokenCambioEmail token = salvato("buono", "preso@y.it", Tempo.adesso().plusMinutes(10), null);
        when(utenti.existsByEmail("preso@y.it")).thenReturn(true);

        assertThatThrownBy(() -> service.confermaEmail("buono"))
                .isInstanceOf(ConflictException.class).hasMessage("Email preso@y.it già registrata");

        verify(tokens).segnaUsato(eq(token.getId()), any(LocalDateTime.class));
        verify(utenti, never()).save(any());
        assertThat(mario.getEmail()).isEqualTo("mario@x.it");
    }

    /* ── Eliminazione ── */

    // D6: l'ADMIN non si elimina dall'app (il seeder lo ricreerebbe e il seed demo resterebbe senza proprietario)
    @Test
    void eliminazioneDellAdmin_403SenzaToccareIlDatabase() {
        Utente admin = conId("admin@x.it", Ruolo.ADMIN);

        assertThatThrownBy(() -> service.elimina(admin, "vecchia-password"))
                .isInstanceOf(ForbiddenException.class).hasMessage(ProfiloService.ADMIN_DALLA_CONFIGURAZIONE);

        verifyNoInteractions(utenti, encoder);
    }

    @Test
    void eliminazioneConLaPasswordSbagliata_400_eNonEliminaNiente() {
        assertThatThrownBy(() -> service.elimina(mario, "sbagliata"))
                .isInstanceOf(BadRequestException.class).hasMessage(ProfiloService.PASSWORD_ATTUALE_ERRATA);

        verify(utenti, never()).deleteById(any());
    }

    // Il resto (sessioni, token, leghe con tappe e pubblicazioni) lo cancella il database con le sue chiavi esterne
    @Test
    void eliminazione_cancellaLaRigaDellUtente_eNelLogLasciaSoloLId() {
        service.elimina(mario, "vecchia-password");

        verify(utenti).deleteById(mario.getId());
        assertThat(log.righe()).containsExactly("Account eliminato: utente " + mario.getId());
        assertThat(log.righe()).noneSatisfy(riga -> assertThat(riga).contains("mario@x.it"));
    }
}

