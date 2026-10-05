package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.PubTappaDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.ArchivioTappa;
import com.hoop3x3.backend.entities.Lega;
import com.hoop3x3.backend.entities.Tappa;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ConflictException;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.repositories.ArchivioTappaRepository;
import com.hoop3x3.backend.repositories.TappaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Archivio circuito: snapshot pubblici delle tappe concluse. */
@Service
public class ArchivioService {

    private final ArchivioTappaRepository repo;
    private final TappaRepository tappe;
    private final LegaService legaService;
    private final AccessGuard guard;
    private final ObjectMapper mapper;

    public ArchivioService(ArchivioTappaRepository repo, TappaRepository tappe, LegaService legaService,
                           AccessGuard guard, ObjectMapper mapper) {
        this.repo = repo;
        this.tappe = tappe;
        this.legaService = legaService;
        this.guard = guard;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<PubTappaDTO> tutte() {
        return repo.findAllByOrderByPubblicatoIlDesc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PubTappaDTO una(UUID tappaId) {
        return toDto(trova(tappaId));
    }

    /**
     * Upsert: la prima pubblicazione crea lo snapshot, le successive lo aggiornano. Il client non manda niente: lo
     * snapshot lo costruisce il server dalla tappa che ha salvato (la forma delle API delle tappe), quindi nessuno
     * può pubblicare risultati inventati. Pubblica il proprietario della lega o un ADMIN, e solo una tappa conclusa.
     * L'autore è sempre il proprietario della lega, anche quando pubblica un ADMIN o si ripubblica: così lui e gli
     * ADMIN possono sempre ritirarla con {@link #rimuovi}.
     */
    @Transactional
    public PubTappaDTO pubblica(Utente utente, UUID tappaId) {
        Tappa tappa = tappe.findById(tappaId).orElseThrow(() -> new NotFoundException("Tappa non trovata: " + tappaId));
        Lega lega = tappa.getLega();
        // Il 403 viene prima del 409: chi non è il proprietario non deve poter scoprire se la tappa è conclusa
        guard.checkOwner(utente, lega.getOwner().getId(), "questa tappa");
        if (!tappa.isConclusa()) {
            throw new ConflictException("La tappa non è conclusa: concludila prima di pubblicarla in archivio");
        }
        ArchivioTappa a = repo.findById(tappaId).orElseGet(ArchivioTappa::new);
        a.setTappaId(tappaId);
        a.setAutore(lega.getOwner());
        a.setLegaNome(lega.getNome());
        a.setContenuto(mapper.writeValueAsString(legaService.toDto(tappa)));
        a.setPubblicatoIl(LocalDateTime.now());
        return toDto(repo.save(a));
    }

    @Transactional
    public void rimuovi(Utente utente, UUID tappaId) {
        ArchivioTappa a = trova(tappaId);
        guard.checkOwner(utente, a.getAutore().getId(), "questa pubblicazione");
        repo.delete(a);
    }

    private ArchivioTappa trova(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Tappa non presente in archivio: " + id));
    }

    private PubTappaDTO toDto(ArchivioTappa a) {
        TappaDTO tappa = mapper.readValue(a.getContenuto(), TappaDTO.class);
        long ts = a.getPubblicatoIl().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new PubTappaDTO(tappa, a.getLegaNome(), a.getAutore().getNome(), a.getAutore().getId(), ts);
    }
}
