package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.TokenRecuperoPassword;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TokenRecuperoPasswordRepository extends JpaRepository<TokenRecuperoPassword, UUID> {

    /**
     * Il token del link, cercato per hash sull'indice unico. Carica anche l'utente: il servizio gli cambia la password.
     * L'uguaglianza della query non è a tempo costante, e non serve che lo sia: si confronta l'hash di un token di 256 bit
     * casuali, e scoprirne qualche carattere dai tempi di risposta non aiuta a trovare un token che lo produca.
     */
    @EntityGraph(attributePaths = "utente")
    Optional<TokenRecuperoPassword> findByTokenHash(String tokenHash);

    /** Quanti token ha ricevuto l'utente da `da` in poi, usati o no: il limite di richieste per email */
    @Query("select count(t) from TokenRecuperoPassword t where t.utente.id = :utenteId and t.creatoIl > :da")
    long contaCreatiDopo(@Param("utenteId") UUID utenteId, @Param("da") LocalDateTime da);

    /** Segna come usati tutti i token ancora utilizzabili dell'utente: vale solo il link più recente, o nessuno */
    @Modifying
    @Query("update TokenRecuperoPassword t set t.usatoIl = :adesso where t.utente.id = :utenteId and t.usatoIl is null")
    int invalidaDellUtente(@Param("utenteId") UUID utenteId, @Param("adesso") LocalDateTime adesso);

    /** Segna il token come usato solo se non lo era: restituisce 1 alla sola richiesta che arriva per prima */
    @Modifying
    @Query("update TokenRecuperoPassword t set t.usatoIl = :adesso where t.id = :id and t.usatoIl is null")
    int segnaUsato(@Param("id") UUID id, @Param("adesso") LocalDateTime adesso);
}
