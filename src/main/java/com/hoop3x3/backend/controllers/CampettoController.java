package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.CampettoDTO;
import com.hoop3x3.backend.dto.CampettoRequestDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.services.CampettoService;
import com.hoop3x3.backend.support.Testo;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Campetti geolocalizzati. La GET è pubblica (vedi SecurityConfig): all'ospite risponde con le stesse chiavi ma senza l'id
 * dell'autore, come l'anagrafe; le scritture richiedono il login, e modifica o elimina solo l'autore o un ADMIN
 * (CampettoService).
 */
@RestController
@RequestMapping("/api/campetti")
public class CampettoController {

    /** Il raggio più grande che si può chiedere: oltre, la ricerca non è più «vicino a me» ed è un elenco intero */
    private static final double RAGGIO_MASSIMO_KM = 500;

    private final CampettoService campettoService;

    public CampettoController(CampettoService campettoService) {
        this.campettoService = campettoService;
    }

    /**
     * Due modi: per raggio (`lat`, `lng`, `raggioKm`: i campetti entro il raggio, dal più vicino) e per testo (`q` nel nome
     * o nella città, ordinati per città e nome, o per distanza se ci sono anche `lat` e `lng`). Con `raggioKm` vince il raggio.
     * Sempre al massimo 200 righe. Senza parametri validi, 400 con il nome del parametro: i controlli stanno qui e non in
     * un @Validated sui parametri perché il 400 deve dire quale parametro non va, come per i campi del corpo
     */
    @GetMapping
    public List<CampettoDTO> cerca(@AuthenticationPrincipal Utente utente, @RequestParam(required = false) Double lat,
                                   @RequestParam(required = false) Double lng, @RequestParam(required = false) Double raggioKm,
                                   @RequestParam(required = false) String q) {
        List<CampettoDTO> trovati = trova(lat, lng, raggioKm, q);
        // Senza utente (ospite) il nome dell'autore resta, il suo id no; con un account qualsiasi la forma completa
        if (utente == null) return trovati.stream().map(CampettoDTO::senzaAutoreId).toList();
        return trovati;
    }

    private List<CampettoDTO> trova(Double lat, Double lng, Double raggioKm, String q) {
        boolean conPunto = lat != null && lng != null;
        if ((lat == null) != (lng == null)) throw new BadRequestException("lat e lng vanno indicati insieme");
        // I confronti negati respingono anche NaN, che passerebbe un «lat < -90 || lat > 90»
        if (conPunto && !(lat >= -90 && lat <= 90)) throw new BadRequestException("lat: deve essere tra -90 e 90");
        if (conPunto && !(lng >= -180 && lng <= 180)) throw new BadRequestException("lng: deve essere tra -180 e 180");
        if (raggioKm != null) {
            if (!conPunto) throw new BadRequestException("raggioKm: servono anche lat e lng");
            if (!(raggioKm > 0 && raggioKm <= RAGGIO_MASSIMO_KM)) {
                throw new BadRequestException("raggioKm: deve essere maggiore di 0 e al massimo 500");
            }
            return campettoService.cercaPerRaggio(lat, lng, raggioKm);
        }
        String testo = Testo.ripulito(q);
        if (!testo.isEmpty()) return campettoService.cercaPerTesto(testo, lat, lng);
        throw new BadRequestException("Indica lat, lng e raggioKm (ricerca per raggio) oppure q (ricerca per testo)");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampettoDTO crea(@AuthenticationPrincipal Utente utente, @RequestBody @Validated CampettoRequestDTO dto) {
        return campettoService.crea(utente, dto);
    }

    @PutMapping("/{id}")
    public CampettoDTO aggiorna(@AuthenticationPrincipal Utente utente, @PathVariable UUID id,
                                @RequestBody @Validated CampettoRequestDTO dto) {
        return campettoService.aggiorna(utente, id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void elimina(@AuthenticationPrincipal Utente utente, @PathVariable UUID id) {
        campettoService.elimina(utente, id);
    }
}
