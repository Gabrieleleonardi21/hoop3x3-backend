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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Anagrafe condivisa del circuito: lettura pubblica, scrittura di autore o ADMIN. */
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
        applica(dto, g);
        guard.tracciaModifica(utente, g.getAutore().getId(), "giocatore", id);
        return GiocatoreDTO.from(giocatori.save(g));
    }

    @Transactional
    public void eliminaGiocatore(Utente utente, UUID id) {
        AnagrafeGiocatore g = trovaGiocatore(id);
        guard.checkOwner(utente, g.getAutore().getId(), "questa scheda giocatore");
        guard.tracciaEliminazione(utente, g.getAutore().getId(), "giocatore", id);
        // Toglie il giocatore dai roster che lo contengono (la FK del ponte è ON DELETE CASCADE,
        // ma Hibernate va tenuto allineato per non lasciare buchi nell'@OrderColumn). Si caricano solo quelle squadre,
        // non tutte, e sono già gestite: la modifica del roster la salva la transazione
        for (AnagrafeSquadra s : squadre.findByRosterContains(g)) {
            s.getRoster().removeIf(x -> x.getId().equals(id));
        }
        giocatori.delete(g);
    }

    /* ── Squadre ── */

    @Transactional(readOnly = true)
    public List<SquadraDTO> tutteSquadre() {
        return squadre.findAllByOrderByModificatoIlDesc().stream().map(SquadraDTO::from).toList();
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
        applica(dto, s);
        guard.tracciaModifica(utente, s.getAutore().getId(), "squadra", id);
        return SquadraDTO.from(squadre.save(s));
    }

    @Transactional
    public void eliminaSquadra(Utente utente, UUID id) {
        AnagrafeSquadra s = trovaSquadra(id);
        guard.checkOwner(utente, s.getAutore().getId(), "questa squadra");
        guard.tracciaEliminazione(utente, s.getAutore().getId(), "squadra", id);
        squadre.delete(s);
    }

    /* ── Helper ── */

    private AnagrafeGiocatore trovaGiocatore(UUID id) {
        return giocatori.findById(id).orElseThrow(() -> new NotFoundException("Giocatore non trovato: " + id));
    }

    private AnagrafeSquadra trovaSquadra(UUID id) {
        return squadre.findById(id).orElseThrow(() -> new NotFoundException("Squadra non trovata: " + id));
    }

    private void applica(GiocatoreRequestDTO d, AnagrafeGiocatore g) {
        g.setNome(d.nome().trim());
        g.setCognome(d.cognome().trim());
        g.setSoprannome(v(d.soprannome()));
        g.setNascita(v(d.nascita()));
        g.setCitta(v(d.citta()));
        g.setNazionalita(v(d.nazionalita()));
        g.setAltezza(v(d.altezza()));
        g.setPeso(v(d.peso()));
        g.setRuolo(v(d.ruolo()));
        g.setNumero(v(d.numero()));
        g.setSquadra(v(d.squadra()));
        g.setEsperienza(v(d.esperienza()));
        g.setNote(v(d.note()));
    }

    private void applica(SquadraRequestDTO d, AnagrafeSquadra s) {
        s.setNome(d.nome().trim());
        s.setCitta(v(d.citta()));
        s.setAnno(v(d.anno()));
        s.setRank(v(d.rank()));
        s.setReferente(v(d.referente()));
        s.setLogo(v(d.logo()));
        s.setWebsite(v(d.website()));
        s.setInstagram(v(d.instagram()));
        s.setNote(v(d.note()));
        // Roster: id sconosciuti vengono ignorati, doppioni rimossi mantenendo l'ordine
        List<AnagrafeGiocatore> roster = new ArrayList<>();
        if (d.roster() != null) {
            for (UUID gid : d.roster().stream().distinct().toList()) {
                giocatori.findById(gid).ifPresent(roster::add);
            }
        }
        s.getRoster().clear();
        s.getRoster().addAll(roster);
    }

    /** Campi opzionali: null → stringa vuota (le colonne sono NOT NULL DEFAULT '') */
    private static String v(String s) {
        if (s == null) return "";
        return s.trim();
    }
}
