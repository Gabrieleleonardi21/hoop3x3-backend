package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnagrafeGiocatoreRepository extends JpaRepository<AnagrafeGiocatore, UUID> {

    /** L'autore arriva con la stessa query: GiocatoreDTO ne legge nome e id per ogni giocatore, e a richiesta sarebbe una query per autore */
    @EntityGraph(attributePaths = "autore")
    List<AnagrafeGiocatore> findAllByOrderByModificatoIlDesc();
}
