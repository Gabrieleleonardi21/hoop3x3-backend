package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnagrafeGiocatoreRepository extends JpaRepository<AnagrafeGiocatore, UUID> {
    List<AnagrafeGiocatore> findAllByOrderByModificatoIlDesc();
}
