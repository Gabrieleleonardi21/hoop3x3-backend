package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.Lega;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LegaRepository extends JpaRepository<Lega, UUID> {
    List<Lega> findByOwnerIdOrderByModificatoIlDesc(UUID ownerId);
}
