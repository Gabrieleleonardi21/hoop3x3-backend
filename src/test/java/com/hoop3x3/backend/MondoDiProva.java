package com.hoop3x3.backend;

import com.hoop3x3.backend.dto.GiocatoreRequestDTO;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.LegaService;

import java.util.List;
import java.util.UUID;

/**
 * Le cose di un utente per i test di integrazione che ne hanno bisogno (AccessoEndpointIT, CancellazioniACascataIT): un
 * giocatore nel roster di una squadra, e una lega con due tappe concluse, entrambe pubblicate in archivio. Si creano con i
 * servizi, come le creerebbe l'applicazione, in un posto solo: un campo nuovo nei DTO si ritocca qui.
 */
public final class MondoDiProva {

    /** Gli id di ciò che `crea` ha messo nel database */
    public record Mondo(Utente utente, UUID giocatore, UUID squadra, UUID lega, List<UUID> tappe) {}

    private final AnagrafeService anagrafeService;
    private final LegaService legaService;
    private final ArchivioService archivioService;

    public MondoDiProva(AnagrafeService anagrafeService, LegaService legaService, ArchivioService archivioService) {
        this.anagrafeService = anagrafeService;
        this.legaService = legaService;
        this.archivioService = archivioService;
    }

    /** Le cose di `utente`: i nomi portano il suo nome, così le righe di più utenti si distinguono */
    public Mondo crea(Utente utente) {
        // Nome e cognome, gli unici campi obbligatori: gli altri restano vuoti
        UUID idGiocatore = anagrafeService.creaGiocatore(utente, new GiocatoreRequestDTO(utente.getNome(), "Rossi", null, null,
                null, null, null, null, null, null, null, null, null, null)).id();
        UUID squadra = anagrafeService.creaSquadra(utente, new SquadraRequestDTO("Squadra di " + utente.getNome(), null, null,
                null, null, null, null, null, null, List.of(idGiocatore), null)).id();

        TappaDTO prima = TappaDiProva.tappa().nome("Prima di " + utente.getNome()).conclusa(true).build();
        TappaDTO seconda = TappaDiProva.tappa().nome("Seconda di " + utente.getNome()).conclusa(true).build();
        UUID lega = legaService.crea(utente, new NuovaLegaDTO("Lega di " + utente.getNome(), List.of(prima, seconda))).id();
        archivioService.pubblica(utente, prima.id());
        archivioService.pubblica(utente, seconda.id());
        return new Mondo(utente, idGiocatore, squadra, lega, List.of(prima.id(), seconda.id()));
    }
}
