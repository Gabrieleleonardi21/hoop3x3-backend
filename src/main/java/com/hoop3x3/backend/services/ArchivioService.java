package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.CopiaPubblicaDTO;
import com.hoop3x3.backend.dto.VoceArchivioDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.support.Tempo;
import com.hoop3x3.backend.entities.ArchivioTappa;
import com.hoop3x3.backend.entities.Lega;
import com.hoop3x3.backend.entities.Tappa;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ConflictException;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.repositories.ArchivioTappaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/** Archivio circuito: snapshot pubblici delle tappe concluse. */
@Service
public class ArchivioService {

    private final ArchivioTappaRepository repo;
    private final LegaService legaService;
    private final AccessGuard guard;
    private final ObjectMapper mapper;

    public ArchivioService(ArchivioTappaRepository repo, LegaService legaService, AccessGuard guard, ObjectMapper mapper) {
        this.repo = repo;
        this.legaService = legaService;
        this.guard = guard;
        this.mapper = mapper;
    }

    /**
     * L'elenco sintetico, dalla pubblicazione più recente: una sola query sulle colonne scritte alla pubblicazione, senza
     * leggere il contenuto delle tappe (che si legge con {@link #una}).
     */
    @Transactional(readOnly = true)
    public List<VoceArchivioDTO> tutte() {
        return repo.elenco().stream()
                .map(v -> new VoceArchivioDTO(v.getTappaId(), v.getNome(), v.getLuogo(), v.getData(),
                        v.getNumeroSquadre(), v.getLega(), v.getAutore(), Tempo.inMillisecondi(v.getPubblicatoIl())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CopiaPubblicaDTO una(UUID tappaId) {
        return toDto(trova(tappaId));
    }

    /**
     * Upsert: la prima pubblicazione crea lo snapshot, le successive lo aggiornano. Il client non manda niente: lo
     * snapshot lo costruisce il server dalla tappa che ha salvato (la forma delle API delle tappe), quindi nessuno
     * può pubblicare risultati inventati. Pubblica il proprietario della lega o un ADMIN, e solo una tappa conclusa.
     * L'autore è sempre il proprietario della lega, anche quando pubblica un ADMIN o si ripubblica: così lui e gli
     * ADMIN possono sempre ritirarla con {@link #rimuovi}.
     * <p>
     * Una pubblicazione già presente la sovrascrive solo il suo autore (o un ADMIN). Gli id delle tappe sono pubblici e li
     * sceglie il client: senza questo controllo chiunque potrebbe creare una tappa con l'id di una pubblicazione orfana
     * (la tappa eliminata prima della V2, la pubblicazione rimasta) e, pubblicandola, sostituirne contenuto e autore.
     */
    @Transactional
    public CopiaPubblicaDTO pubblica(Utente utente, UUID tappaId) {
        // 404 se la tappa non esiste e 403 se non è del proprietario né di un ADMIN: la regola è quella di LegaService per le
        // sue tappe, scritta una volta sola. Vengono prima del 409: chi non è il proprietario non deve poter scoprire se la
        // tappa è conclusa
        Tappa tappa = legaService.trovaTappa(utente, tappaId);
        ArchivioTappa a = repo.findById(tappaId).orElseGet(ArchivioTappa::new);
        // La riga c'è già: 403 se il suo autore non è chi pubblica (un ADMIN passa). Prima del 409, come l'altro 403
        if (a.getAutore() != null) guard.checkOwner(utente, a.getAutore().getId(), "questa pubblicazione");
        if (!tappa.isConclusa()) {
            throw new ConflictException("La tappa non è conclusa: concludila prima di pubblicarla in archivio");
        }
        Lega lega = tappa.getLega();
        guard.tracciaModifica(utente, lega.getOwner().getId(), "pubblicazione", tappaId);
        a.setTappaId(tappaId);
        a.setAutore(lega.getOwner());
        a.setLegaNome(lega.getNome());
        TappaDTO snapshot = legaService.toDto(tappa);
        a.setContenuto(mapper.writeValueAsString(snapshot));
        // Le colonne dell'elenco pubblico: copie di ciò che sta nello snapshot, così l'elenco non lo decomprime
        a.setNome(snapshot.nome());
        a.setLuogo(snapshot.luogo());
        a.setData(snapshot.data());
        a.setNumeroSquadre(snapshot.squadre().size());
        a.setPubblicatoIl(Tempo.adesso());
        return toDto(repo.save(a));
    }

    @Transactional
    public void rimuovi(Utente utente, UUID tappaId) {
        ArchivioTappa a = trova(tappaId);
        guard.checkOwner(utente, a.getAutore().getId(), "questa pubblicazione");
        guard.tracciaEliminazione(utente, a.getAutore().getId(), "pubblicazione", tappaId);
        repo.delete(a);
    }

    private ArchivioTappa trova(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Tappa non presente in archivio: " + id));
    }

    private CopiaPubblicaDTO toDto(ArchivioTappa a) {
        TappaDTO tappa = mapper.readValue(a.getContenuto(), TappaDTO.class);
        return new CopiaPubblicaDTO(tappa, a.getLegaNome(), a.getAutore().getNome(), a.getAutore().getId(),
                Tempo.inMillisecondi(a.getPubblicatoIl()));
    }
}
