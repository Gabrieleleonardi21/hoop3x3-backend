package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.Tappa;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TappaRepository extends JpaRepository<Tappa, UUID> {
    int countByLegaId(UUID legaId);
}
