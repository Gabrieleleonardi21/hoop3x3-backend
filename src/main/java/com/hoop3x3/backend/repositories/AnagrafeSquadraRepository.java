package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnagrafeSquadraRepository extends JpaRepository<AnagrafeSquadra, UUID> {

    /**
     * Roster e autore arrivano con la stessa query: SquadraDTO li legge per ogni squadra (open-in-view=false), e caricati a
     * richiesta sarebbero due query in più per riga. Il roster è una lista con @OrderColumn, non una «bag»: caricarlo con un
     * join non cambia l'ordine dei giocatori (lo dà la posizione salvata) e non dà MultipleBagFetchException.
     */
    @EntityGraph(attributePaths = {"roster", "autore"})
    List<AnagrafeSquadra> findAllByOrderByModificatoIlDesc();

    /**
     * Le squadre che hanno il giocatore nel roster, ognuna con il roster intero e con la stessa query. Il filtro è
     * «member of», non un join fetch sul roster con la condizione sul giocatore: quello caricherebbe in ogni roster solo
     * il giocatore cercato, e salvare la squadra senza di lui cancellerebbe tutti gli altri.
     */
    @EntityGraph(attributePaths = "roster")
    List<AnagrafeSquadra> findByRosterContains(AnagrafeGiocatore giocatore);
}
