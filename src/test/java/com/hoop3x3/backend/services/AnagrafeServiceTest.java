package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La riga con cui AccessGuard registra l'intervento di un ADMIN dice che l'intervento c'è stato: il servizio la chiede
 * dopo aver preparato la scrittura (applica), subito prima di scrivere, e non prima. Senza contesto Spring: i repository
 * e la guardia sono simulati (la guardia vera, e la riga che scrive, le prova LogApplicativiTest).
 */
class AnagrafeServiceTest {

    private final AnagrafeGiocatoreRepository giocatori = mock(AnagrafeGiocatoreRepository.class);
    private final AnagrafeSquadraRepository squadre = mock(AnagrafeSquadraRepository.class);
    private final AccessGuard guard = mock(AccessGuard.class);
    private final AnagrafeService servizio = new AnagrafeService(giocatori, squadre, guard);

    private final Utente mario = utente("mario@test.it", Ruolo.USER);
    private final Utente admin = utente("admin@test.it", Ruolo.ADMIN);
    private final UUID idSquadra = UUID.randomUUID();
    private final UUID idGiocatore = UUID.randomUUID();

    // Una squadra di mario con un giocatore nel roster: l'ADMIN la modifica, e applica legge il giocatore dal repository
    private AnagrafeSquadra squadraDiMario() {
        AnagrafeSquadra squadra = new AnagrafeSquadra();
        ReflectionTestUtils.setField(squadra, "id", idSquadra);
        squadra.setNome("Roma 3x3");
        squadra.setAutore(mario);
        squadra.setModificatoIl(LocalDateTime.now());
        when(squadre.findById(idSquadra)).thenReturn(Optional.of(squadra));
        when(squadre.save(any(AnagrafeSquadra.class))).thenAnswer(chiamata -> chiamata.getArgument(0));
        return squadra;
    }

    private SquadraRequestDTO modificaDelRoster() {
        return new SquadraRequestDTO("Roma 3x3", null, null, null, null, null, null, null, null, List.of(idGiocatore));
    }

    @Test
    void aggiornaSquadra_laRigaDellAdminVieneDopoLaLetturaDelRoster() {
        squadraDiMario();
        when(giocatori.findById(idGiocatore)).thenReturn(Optional.of(new AnagrafeGiocatore()));

        servizio.aggiornaSquadra(admin, idSquadra, modificaDelRoster());

        InOrder ordine = inOrder(giocatori, guard);
        ordine.verify(giocatori).findById(idGiocatore); // applica: il roster si prepara...
        ordine.verify(guard).tracciaModifica(admin, mario.getId(), "squadra", idSquadra); // ...e solo dopo la riga
    }

    // Se la preparazione della scrittura fallisce non è cambiato niente: nessuna riga che dica il contrario
    @Test
    void aggiornaSquadra_seLaPreparazioneFallisce_nonLasciaLaRiga() {
        squadraDiMario();
        when(giocatori.findById(idGiocatore)).thenThrow(new IllegalStateException("database non raggiungibile"));

        assertThatThrownBy(() -> servizio.aggiornaSquadra(admin, idSquadra, modificaDelRoster()))
                .isInstanceOf(IllegalStateException.class);

        verify(guard, never()).tracciaModifica(any(), any(), any(), any());
    }

    private static Utente utente(String email, Ruolo ruolo) {
        Utente utente = new Utente(email, "hash", "Nome", ruolo);
        ReflectionTestUtils.setField(utente, "id", UUID.randomUUID());
        return utente;
    }
}
