package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.RefreshToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    /** Carica anche l'utente: il controller ne legge nome, email e ruolo fuori dalla transazione (open-in-view=false) */
    @EntityGraph(attributePaths = "utente")
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Pulizia pigra: via i token scaduti dell'utente ogni volta che se ne emette uno nuovo */
    void deleteByUtente_IdAndScadeIlBefore(UUID utenteId, LocalDateTime limite);

    /** Cancellazione atomica: restituisce 1 solo alla richiesta che arriva per prima sullo stesso token */
    @Modifying
    @Query("delete from RefreshToken r where r.tokenHash = :tokenHash")
    int eliminaPerHash(@Param("tokenHash") String tokenHash);
}
