package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.PubTappaDTO;
import com.hoop3x3.backend.dto.PubblicaTappaDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.ArchivioTappa;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.repositories.ArchivioTappaRepository;
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
    private final AccessGuard guard;
    private final ObjectMapper mapper;

    public ArchivioService(ArchivioTappaRepository repo, AccessGuard guard, ObjectMapper mapper) {
        this.repo = repo;
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

    /** Upsert: la prima pubblicazione crea lo snapshot, le successive lo aggiornano (solo autore o ADMIN) */
    @Transactional
    public PubTappaDTO pubblica(Utente utente, PubblicaTappaDTO dto) {
        ArchivioTappa a = repo.findById(dto.tappa().id()).orElse(null);
        if (a == null) {
            a = new ArchivioTappa();
            a.setTappaId(dto.tappa().id());
            a.setAutore(utente);
        } else {
            guard.checkOwner(utente, a.getAutore().getId(), "questa pubblicazione");
        }
        a.setLegaNome(dto.lega().trim());
        a.setContenuto(mapper.writeValueAsString(dto.tappa()));
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
