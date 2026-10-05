package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.*;
import com.hoop3x3.backend.entities.Lega;
import com.hoop3x3.backend.entities.Tappa;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ConflictException;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.repositories.LegaRepository;
import com.hoop3x3.backend.repositories.TappaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Leghe e tappe dell'utente: ogni operazione verifica che la lega sia sua (o che sia ADMIN). */
@Service
public class LegaService {

    private final LegaRepository legaRepository;
    private final TappaRepository tappaRepository;
    private final AccessGuard guard;
    private final JsonSupport json;

    public LegaService(LegaRepository legaRepository, TappaRepository tappaRepository, AccessGuard guard, JsonSupport json) {
        this.legaRepository = legaRepository;
        this.tappaRepository = tappaRepository;
        this.guard = guard;
        this.json = json;
    }

    /* ── Leghe ── */

    @Transactional(readOnly = true)
    public List<LegaMetaDTO> indice(Utente utente) {
        // Una sola query: le leghe con il numero delle loro tappe, contate dal database senza caricarle
        return legaRepository.indiceDi(utente.getId()).stream()
                .map(v -> toMeta(v.getId(), v.getNome(), v.getModificatoIl(), v.getNumeroTappe()))
                .toList();
    }

    @Transactional
    public LegaMetaDTO crea(Utente utente, NuovaLegaDTO dto) {
        Lega lega = new Lega(dto.nome().trim(), utente);
        // Import da file: le tappe arrivano già complete, si aggiungono in ordine
        if (dto.tappe() != null) {
            int pos = 0;
            for (TappaDTO t : dto.tappe()) {
                if (tappaRepository.existsById(t.id())) throw new ConflictException("Esiste già una tappa con id " + t.id());
                lega.getTappe().add(fromDto(t, lega, pos++));
            }
        }
        return toMeta(legaRepository.save(lega));
    }

    @Transactional(readOnly = true)
    public LegaDettaglioDTO dettaglio(Utente utente, UUID id) {
        Lega lega = trovaLega(utente, id);
        List<TappaDTO> tappe = lega.getTappe().stream().map(this::toDto).toList();
        return new LegaDettaglioDTO(lega.getId(), lega.getNome(), tappe);
    }

    @Transactional
    public LegaMetaDTO rinomina(Utente utente, UUID id, PatchLegaDTO dto) {
        Lega lega = trovaLega(utente, id);
        lega.setNome(dto.nome().trim());
        return toMeta(legaRepository.save(lega));
    }

    @Transactional
    public void elimina(Utente utente, UUID id) {
        legaRepository.delete(trovaLega(utente, id));
    }

    /* ── Tappe ── */

    @Transactional
    public TappaDTO aggiungiTappa(Utente utente, UUID legaId, TappaDTO dto) {
        Lega lega = trovaLega(utente, legaId);
        if (tappaRepository.existsById(dto.id())) throw new ConflictException("Esiste già una tappa con id " + dto.id());
        // In coda: una posizione dopo la massima, non il numero delle tappe (dopo un'eliminazione sarebbe già di un'altra).
        // Due richieste insieme nella stessa lega potrebbero prendere la stessa posizione, ma non succede: le fa chi
        // gestisce la lega (il proprietario o un ADMIN) e la coda del frontend le manda una alla volta
        Tappa t = fromDto(dto, lega, tappaRepository.prossimaPosizione(legaId));
        lega.getTappe().add(t);
        lega.touch();
        legaRepository.save(lega);
        return toDto(t);
    }

    /** Sostituzione completa della tappa (il frontend manda sempre l'oggetto intero) */
    @Transactional
    public TappaDTO aggiornaTappa(Utente utente, UUID tappaId, TappaDTO dto) {
        Tappa t = trovaTappa(utente, tappaId);
        applica(dto, t);
        t.getLega().touch();
        return toDto(tappaRepository.save(t));
    }

    @Transactional
    public void eliminaTappa(Utente utente, UUID tappaId) {
        Tappa t = trovaTappa(utente, tappaId);
        Lega lega = t.getLega();
        lega.getTappe().remove(t); // orphanRemoval cancella la riga
        lega.touch();
        legaRepository.save(lega);
    }

    /* ── Helper ── */

    private Lega trovaLega(Utente utente, UUID id) {
        Lega lega = legaRepository.findById(id).orElseThrow(() -> new NotFoundException("Lega non trovata: " + id));
        guard.checkOwner(utente, lega.getOwner().getId(), "questa lega");
        return lega;
    }

    /**
     * Carica la tappa (404 se non esiste) e verifica che l'utente sia il proprietario della sua lega o un ADMIN (403).
     * Visibile nel package perché la usa anche ArchivioService: la regola di accesso a una tappa sta in un posto solo.
     * Va chiamata dentro una transazione: la lega e il suo proprietario si caricano a richiesta.
     */
    Tappa trovaTappa(Utente utente, UUID id) {
        Tappa t = tappaRepository.findById(id).orElseThrow(() -> new NotFoundException("Tappa non trovata: " + id));
        guard.checkOwner(utente, t.getLega().getOwner().getId(), "questa tappa");
        return t;
    }

    /** Voce dell'indice di una lega già letta (dopo la creazione e la rinomina): le tappe si contano con una query, non si caricano */
    private LegaMetaDTO toMeta(Lega l) {
        return toMeta(l.getId(), l.getNome(), l.getModificatoIl(), tappaRepository.countByLegaId(l.getId()));
    }

    private static LegaMetaDTO toMeta(UUID id, String nome, LocalDateTime modificatoIl, int nTappe) {
        long ts = modificatoIl.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new LegaMetaDTO(id, nome, ts, nTappe);
    }

    private Tappa fromDto(TappaDTO dto, Lega lega, int posizione) {
        Tappa t = new Tappa();
        t.setId(dto.id());
        t.setLega(lega);
        t.setPosizione(posizione);
        applica(dto, t);
        return t;
    }

    /** Copia i campi del DTO sull'entity (usato sia in creazione che in aggiornamento) */
    private void applica(TappaDTO dto, Tappa t) {
        t.setNome(dto.nome().trim());
        t.setLuogo(valore(dto.luogo()));
        t.setData(valore(dto.data()));
        t.setNGironi(dto.nGironi());
        t.setConclusa(Boolean.TRUE.equals(dto.conclusa()));
        t.setRegole(dto.regole().toEntity());
        t.setSquadre(json.arrayOrEmpty(dto.squadre(), "squadre"));
        t.setGironi(json.arrayOrNull(dto.gironi(), "gironi"));
        t.setPartite(json.arrayOrEmpty(dto.partite(), "partite"));
        t.setBracket(json.arrayOrNull(dto.bracket(), "bracket"));
        t.setVideo(json.arrayOrEmpty(dto.video(), "video"));
    }

    public TappaDTO toDto(Tappa t) {
        return new TappaDTO(t.getId(), t.getNome(), t.getLuogo(), t.getData(), t.getNGironi(),
                RegoleDTO.from(t.getRegole()), json.parse(t.getSquadre()), json.parse(t.getGironi()),
                json.parse(t.getPartite()), json.parse(t.getVideo()), t.isConclusa(), json.parse(t.getBracket()));
    }

    private static String valore(String s) {
        if (s == null) return "";
        return s.trim();
    }
}
