package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.GiocatoreDTO;
import com.hoop3x3.backend.dto.GiocatoreRequestDTO;
import com.hoop3x3.backend.dto.SquadraDTO;
import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import com.hoop3x3.backend.support.Testo;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Anagrafe condivisa del circuito: lettura pubblica, scrittura di autore o ADMIN. Gli elenchi hanno due forme: la completa
 * per chi ha un account e la pubblica, senza i dati personali, per l'ospite (sceglie il controller).
 */
@Service
public class AnagrafeService {

    private final AnagrafeGiocatoreRepository giocatori;
    private final AnagrafeSquadraRepository squadre;
    private final AccessGuard guard;

    public AnagrafeService(AnagrafeGiocatoreRepository giocatori, AnagrafeSquadraRepository squadre, AccessGuard guard) {
        this.giocatori = giocatori;
        this.squadre = squadre;
        this.guard = guard;
    }

    /* ── Giocatori ── */

    @Transactional(readOnly = true)
    public List<GiocatoreDTO> tuttiGiocatori() {
        return giocatori.findAllByOrderByModificatoIlDesc().stream().map(GiocatoreDTO::from).toList();
    }

    /** Come {@link #tuttiGiocatori} nella forma pubblica: senza i dati personali */
    @Transactional(readOnly = true)
    public List<GiocatoreDTO> tuttiGiocatoriPubblici() {
        return giocatori.findAllByOrderByModificatoIlDesc().stream().map(GiocatoreDTO::pubblico).toList();
    }

    @Transactional
    public GiocatoreDTO creaGiocatore(Utente utente, GiocatoreRequestDTO dto) {
        AnagrafeGiocatore g = new AnagrafeGiocatore();
        g.setAutore(utente);
        applica(dto, g);
        return GiocatoreDTO.from(giocatori.save(g));
    }

    @Transactional
    public GiocatoreDTO aggiornaGiocatore(Utente utente, UUID id, GiocatoreRequestDTO dto) {
        AnagrafeGiocatore g = trovaGiocatore(id);
        guard.checkOwner(utente, g.getAutore().getId(), "questa scheda giocatore");
        controllaVersione(dto.versione(), g.getVersione(), AnagrafeGiocatore.class, id);
        applica(dto, g);
        guard.tracciaModifica(utente, g.getAutore().getId(), "giocatore", id);
        // saveAndFlush: la data di modifica (@PreUpdate) la scrive Hibernate al flush, e senza flush il DTO porterebbe il ts
        // di prima, non quello del salvataggio appena fatto
        return GiocatoreDTO.from(giocatori.saveAndFlush(g));
    }

    @Transactional
    public void eliminaGiocatore(Utente utente, UUID id) {
        AnagrafeGiocatore g = trovaGiocatore(id);
        guard.checkOwner(utente, g.getAutore().getId(), "questa scheda giocatore");
        guard.tracciaEliminazione(utente, g.getAutore().getId(), "giocatore", id);
        // Toglie il giocatore dai roster che lo contengono (la FK del ponte è ON DELETE CASCADE,
        // ma Hibernate va tenuto allineato per non lasciare buchi nell'@OrderColumn). Si caricano solo quelle squadre,
        // non tutte, e sono già gestite: la modifica del roster la salva la transazione. Un null è un buco già presente
        // (lasciato da una cancellazione in SQL, vedi SquadraDTO): si toglie anche lui e il roster si ricompatta
        for (AnagrafeSquadra s : squadre.findByRosterContains(g)) {
            s.getRoster().removeIf(x -> x == null || x.getId().equals(id));
        }
        giocatori.delete(g);
    }

    /* ── Squadre ── */

    @Transactional(readOnly = true)
    public List<SquadraDTO> tutteSquadre() {
        return squadre.findAllByOrderByModificatoIlDesc().stream().map(SquadraDTO::from).toList();
    }

    /** Come {@link #tutteSquadre} nella forma pubblica: senza referente e autore */
    @Transactional(readOnly = true)
    public List<SquadraDTO> tutteSquadrePubbliche() {
        return squadre.findAllByOrderByModificatoIlDesc().stream().map(SquadraDTO::pubblica).toList();
    }

    @Transactional
    public SquadraDTO creaSquadra(Utente utente, SquadraRequestDTO dto) {
        AnagrafeSquadra s = new AnagrafeSquadra();
        s.setAutore(utente);
        applica(dto, s);
        return SquadraDTO.from(squadre.save(s));
    }

    @Transactional
    public SquadraDTO aggiornaSquadra(Utente utente, UUID id, SquadraRequestDTO dto) {
        AnagrafeSquadra s = trovaSquadra(id);
        guard.checkOwner(utente, s.getAutore().getId(), "questa squadra");
        controllaVersione(dto.versione(), s.getVersione(), AnagrafeSquadra.class, id);
        applica(dto, s);
        guard.tracciaModifica(utente, s.getAutore().getId(), "squadra", id);
        return SquadraDTO.from(squadre.saveAndFlush(s)); // flush: il ts della risposta è quello di adesso (vedi aggiornaGiocatore)
    }

    @Transactional
    public void eliminaSquadra(Utente utente, UUID id) {
        AnagrafeSquadra s = trovaSquadra(id);
        guard.checkOwner(utente, s.getAutore().getId(), "questa squadra");
        guard.tracciaEliminazione(utente, s.getAutore().getId(), "squadra", id);
        squadre.delete(s);
    }

    /* ── Helper ── */

    /**
     * La versione che il client ha letto contro quella del database. Facoltativa: un client che non la manda (il frontend di
     * prima, uno script) salva senza controllo, come sempre; chi la manda e la trova cambiata ha davanti una scheda salvata
     * nel frattempo da un altro dispositivo (o da un ADMIN) e riceve 409, senza sovrascriverla. L'eccezione è quella che
     * lancerebbe Hibernate al flush se la scheda cambiasse mentre si salva: le due strade hanno un solo gestore
     * (ExceptionsHandler), con il messaggio generico «I dati sono stati modificati...». Va dopo checkOwner: chi non è
     * l'autore non scopre la versione di una scheda altrui provando dei numeri
     */
    private static void controllaVersione(Long letta, long attuale, Class<?> entity, UUID id) {
        if (letta == null) return;
        if (letta != attuale) throw new ObjectOptimisticLockingFailureException(entity, id);
    }

    private AnagrafeGiocatore trovaGiocatore(UUID id) {
        return giocatori.findById(id).orElseThrow(() -> new NotFoundException("Giocatore non trovato: " + id));
    }

    private AnagrafeSquadra trovaSquadra(UUID id) {
        return squadre.findById(id).orElseThrow(() -> new NotFoundException("Squadra non trovata: " + id));
    }

    private void applica(GiocatoreRequestDTO d, AnagrafeGiocatore g) {
        g.setNome(d.nome().trim());
        g.setCognome(d.cognome().trim());
        g.setSoprannome(Testo.ripulito(d.soprannome()));
        g.setNascita(Testo.ripulito(d.nascita()));
        g.setCitta(Testo.ripulito(d.citta()));
        g.setNazionalita(Testo.ripulito(d.nazionalita()));
        g.setAltezza(Testo.ripulito(d.altezza()));
        g.setPeso(Testo.ripulito(d.peso()));
        g.setRuolo(Testo.ripulito(d.ruolo()));
        g.setNumero(Testo.ripulito(d.numero()));
        g.setSquadra(Testo.ripulito(d.squadra()));
        g.setEsperienza(Testo.ripulito(d.esperienza()));
        g.setNote(Testo.ripulito(d.note()));
    }

    private void applica(SquadraRequestDTO d, AnagrafeSquadra s) {
        s.setNome(d.nome().trim());
        s.setCitta(Testo.ripulito(d.citta()));
        s.setAnno(Testo.ripulito(d.anno()));
        s.setRank(Testo.ripulito(d.rank()));
        s.setReferente(Testo.ripulito(d.referente()));
        s.setLogo(Testo.ripulito(d.logo()));
        s.setWebsite(Testo.ripulito(d.website()));
        s.setInstagram(Testo.ripulito(d.instagram()));
        s.setNote(Testo.ripulito(d.note()));
        // Roster: id sconosciuti vengono ignorati, doppioni rimossi mantenendo l'ordine. I giocatori si leggono con una
        // sola query (findAllById): le righe tornano in un ordine qualsiasi, quindi l'ordine del client si rimette dagli id
        List<UUID> ids = List.of();
        if (d.roster() != null) ids = d.roster().stream().distinct().toList();
        Map<UUID, AnagrafeGiocatore> trovati = giocatori.findAllById(ids).stream()
                .collect(Collectors.toMap(AnagrafeGiocatore::getId, Function.identity()));
        List<AnagrafeGiocatore> roster = new ArrayList<>();
        for (UUID gid : ids) {
            AnagrafeGiocatore g = trovati.get(gid);
            if (g != null) roster.add(g);
        }
        s.getRoster().clear();
        s.getRoster().addAll(roster);
    }
}
