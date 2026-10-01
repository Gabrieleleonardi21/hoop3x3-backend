package com.hoop3x3.backend.services;

import com.hoop3x3.backend.entities.RefreshToken;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import com.hoop3x3.backend.repositories.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Il repository è simulato: si verifica la logica (hash, scadenza, rotazione), non il database. */
class RefreshTokenServiceTest {

    private RefreshTokenRepository repository;
    private RefreshTokenService service;
    private final Utente utente = new Utente("mario@test.it", "hash", "Mario", Ruolo.USER);

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        service = new RefreshTokenService(repository, 30);
    }

    @Test
    void emettiSalvaSoloLHashConScadenzaA30GiorniERestituisceIlTokenInChiaro() {
        String token = service.emetti(utente);

        ArgumentCaptor<RefreshToken> salvato = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(salvato.capture());
        assertThat(token).hasSize(43).doesNotContain("=");            // 32 byte in Base64 URL-safe
        assertThat(salvato.getValue().getTokenHash()).isEqualTo(RefreshTokenService.sha256(token));
        assertThat(salvato.getValue().getTokenHash()).isNotEqualTo(token);
        assertThat(salvato.getValue().getUtente()).isSameAs(utente);
        assertThat(salvato.getValue().getScadeIl())
                .isBetween(LocalDateTime.now().plusDays(30).minusMinutes(1), LocalDateTime.now().plusDays(30).plusMinutes(1));
    }

    @Test
    void emettiFaPuliziaDeiTokenScadutiDellUtente() {
        service.emetti(utente);
        verify(repository).deleteByUtente_IdAndScadeIlBefore(eq(utente.getId()), any(LocalDateTime.class));
    }

    @Test
    void dueTokenEmessiSonoDiversi() {
        assertThat(service.emetti(utente)).isNotEqualTo(service.emetti(utente));
    }

    @Test
    void ruotaCancellaIlVecchioTokenENeEmetteUnoNuovo() {
        RefreshToken vecchio = new RefreshToken(utente, RefreshTokenService.sha256("vecchio"), LocalDateTime.now().plusDays(1));
        when(repository.findByTokenHash(RefreshTokenService.sha256("vecchio"))).thenReturn(Optional.of(vecchio));

        RefreshTokenService.Rinnovo rinnovo = service.ruota("vecchio");

        assertThat(rinnovo.utente()).isSameAs(utente);
        assertThat(rinnovo.nuovoToken()).isNotEqualTo("vecchio").hasSize(43);
        verify(repository).delete(vecchio);
        verify(repository).save(any(RefreshToken.class));
    }

    @Test
    void ruotaRifiutaUnTokenSconosciuto() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ruota("inventato"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("accedi di nuovo");
        verify(repository, never()).save(any());
    }

    @Test
    void ruotaRifiutaUnTokenScaduto() {
        RefreshToken scaduto = new RefreshToken(utente, RefreshTokenService.sha256("vecchio"), LocalDateTime.now().minusMinutes(1));
        when(repository.findByTokenHash(RefreshTokenService.sha256("vecchio"))).thenReturn(Optional.of(scaduto));

        assertThatThrownBy(() -> service.ruota("vecchio")).isInstanceOf(UnauthorizedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void revocaCancellaIlTokenSeEsiste() {
        RefreshToken salvato = new RefreshToken(utente, RefreshTokenService.sha256("vecchio"), LocalDateTime.now().plusDays(1));
        when(repository.findByTokenHash(RefreshTokenService.sha256("vecchio"))).thenReturn(Optional.of(salvato));

        service.revoca("vecchio");

        verify(repository).delete(salvato);
    }

    @Test
    void revocaIgnoraUnTokenSconosciuto() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        service.revoca("inventato");

        verify(repository, never()).delete(any());
    }
}
