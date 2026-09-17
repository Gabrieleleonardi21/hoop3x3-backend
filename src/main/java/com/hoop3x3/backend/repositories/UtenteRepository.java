package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.Utente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UtenteRepository extends JpaRepository<Utente, UUID> {
    Optional<Utente> findByEmail(String email);
    boolean existsByEmail(String email);
}
