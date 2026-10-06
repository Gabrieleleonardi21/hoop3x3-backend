package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Il servizio dell'anagrafe senza contesto Spring: i repository e la guardia sono simulati (la guardia vera, e la riga che
 * scrive, le prova LogApplicativiTest).
 * <ul>
 *   <li>Il roster di una squadra si legge con una lettura sola dei giocatori, nell'ordine richiesto.</li>
 *   <li>La riga con cui AccessGuard registra l'intervento di un ADMIN dice che l'intervento c'è stato: il servizio la chiede
 *   dopo aver preparato la scrittura (applica), subito prima di scrivere, e non prima.</li>
 * </ul>
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
        when(giocatori.findAllById(List.of(idGiocatore))).thenReturn(List.of(giocatore(idGiocatore)));

        servizio.aggiornaSquadra(admin, idSquadra, modificaDelRoster());

        InOrder ordine = inOrder(giocatori, guard);
        ordine.verify(giocatori).findAllById(List.of(idGiocatore)); // applica: il roster si prepara...
        ordine.verify(guard).tracciaModifica(admin, mario.getId(), "squadra", idSquadra); // ...e solo dopo la riga
    }

    // Se la preparazione della scrittura fallisce non è cambiato niente: nessuna riga che dica il contrario
    @Test
    void aggiornaSquadra_seLaPreparazioneFallisce_nonLasciaLaRiga() {
        squadraDiMario();
        when(giocatori.findAllById(any())).thenThrow(new IllegalStateException("database non raggiungibile"));

        assertThatThrownBy(() -> servizio.aggiornaSquadra(admin, idSquadra, modificaDelRoster()))
                .isInstanceOf(IllegalStateException.class);

        verify(guard, never()).tracciaModifica(any(), any(), any(), any());
    }

    /* ── Roster: una lettura sola, nell'ordine richiesto ── */

    // findAllById non garantisce l'ordine delle righe (lo dà il database): il roster deve uscire come l'ha chiesto il client
    @Test
    void aggiornaSquadra_ilRosterSiLeggeConUnaLetturaSolaENelOrdineRichiesto() {
        AnagrafeSquadra squadra = squadraDiMario();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // Il database li restituisce in un altro ordine da quello chiesto (c, a, b)
        when(giocatori.findAllById(List.of(c, a, b))).thenReturn(List.of(giocatore(a), giocatore(b), giocatore(c)));

        servizio.aggiornaSquadra(admin, idSquadra, new SquadraRequestDTO("Roma 3x3", null, null, null, null, null, null,
                null, null, List.of(c, a, b)));

        assertThat(squadra.getRoster()).extracting(AnagrafeGiocatore::getId).containsExactly(c, a, b);
        verify(giocatori, times(1)).findAllById(any());
        verify(giocatori, never()).findById(any());
    }

    // Gli id sconosciuti si ignorano e i doppioni si tolgono, tenendo la prima posizione: all'elenco del database arrivano
    // gli id distinti, e il roster ha solo i giocatori che esistono
    @Test
    void aggiornaSquadra_idSconosciutiEDoppioniSiIgnoranoMantenendoLOrdine() {
        AnagrafeSquadra squadra = squadraDiMario();
        UUID a = UUID.randomUUID();
        UUID sconosciuto = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(giocatori.findAllById(List.of(a, sconosciuto, b))).thenReturn(List.of(giocatore(b), giocatore(a)));

        servizio.aggiornaSquadra(admin, idSquadra, new SquadraRequestDTO("Roma 3x3", null, null, null, null, null, null,
                null, null, List.of(a, sconosciuto, a, b)));

        assertThat(squadra.getRoster()).extracting(AnagrafeGiocatore::getId).containsExactly(a, b);
    }

    @Test
    void creaSquadra_ilRosterSiLeggeConUnaLetturaSolaENelOrdineRichiesto() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // Il repository vero dà alla squadra salvata la data di modifica (@PrePersist): il DTO la legge
        when(squadre.save(any(AnagrafeSquadra.class))).thenAnswer(chiamata -> {
            AnagrafeSquadra s = chiamata.getArgument(0);
            s.setModificatoIl(LocalDateTime.now());
            return s;
        });
        when(giocatori.findAllById(List.of(b, a))).thenReturn(List.of(giocatore(a), giocatore(b)));

        servizio.creaSquadra(mario, new SquadraRequestDTO("Roma 3x3", null, null, null, null, null, null, null, null,
                List.of(b, a)));

        ArgumentCaptor<AnagrafeSquadra> salvata = ArgumentCaptor.forClass(AnagrafeSquadra.class);
        verify(squadre).save(salvata.capture());
        assertThat(salvata.getValue().getRoster()).extracting(AnagrafeGiocatore::getId).containsExactly(b, a);
        verify(giocatori, never()).findById(any());
    }

    // Senza roster nel corpo il roster resta vuoto: niente da leggere
    @Test
    void aggiornaSquadra_senzaRosterNelCorpo_ilRosterSiSvuota() {
        AnagrafeSquadra squadra = squadraDiMario();
        squadra.getRoster().add(giocatore(idGiocatore));

        servizio.aggiornaSquadra(admin, idSquadra, new SquadraRequestDTO("Roma 3x3", null, null, null, null, null, null,
                null, null, null));

        assertThat(squadra.getRoster()).isEmpty();
        verify(giocatori, never()).findById(any());
    }

    /** Un giocatore con quell'id (l'entity non ha il setter: lo assegna il database) */
    private static AnagrafeGiocatore giocatore(UUID id) {
        AnagrafeGiocatore g = new AnagrafeGiocatore();
        ReflectionTestUtils.setField(g, "id", id);
        return g;
    }

    private static Utente utente(String email, Ruolo ruolo) {
        Utente utente = new Utente(email, "hash", "Nome", ruolo);
        ReflectionTestUtils.setField(utente, "id", UUID.randomUUID());
        return utente;
    }
}
