package com.hoop3x3.backend.services;

import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static com.hoop3x3.backend.UtenteDiProva.conId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private final ProfiloService service = new ProfiloService(utenti, sessioni, encoder);

    private final Utente mario = conId("mario@x.it");
    private LogCatturato log;

    @BeforeEach
    void setUp() {
        // L'hash di mario è «hash» (UtenteDiProva): solo «vecchia-password» lo verifica
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        when(encoder.matches("vecchia-password", "hash")).thenReturn(true);
        when(encoder.encode(anyString())).thenReturn("hash-nuovo");
        when(utenti.save(any(Utente.class))).thenAnswer(chiamata -> chiamata.getArgument(0));
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
}
