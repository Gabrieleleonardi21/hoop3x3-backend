package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.AnagrafeSquadra;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnagrafeSquadraRepository extends JpaRepository<AnagrafeSquadra, UUID> {
    List<AnagrafeSquadra> findAllByOrderByModificatoIlDesc();
}
