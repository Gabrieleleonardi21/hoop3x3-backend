package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.TokenCambioEmail;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** I token del cambio email: le stesse query di TokenRecuperoPasswordRepository, sulla tabella della V10 */
public interface TokenCambioEmailRepository extends JpaRepository<TokenCambioEmail, UUID> {

    /** Il token del link, cercato per hash sull'indice unico. Carica anche l'utente: il servizio gli cambia l'email */
    @EntityGraph(attributePaths = "utente")
    Optional<TokenCambioEmail> findByTokenHash(String tokenHash);

    /** Quanti token ha ricevuto l'utente da `da` in poi, usati o no: il limite di richieste per account */
    @Query("select count(t) from TokenCambioEmail t where t.utente.id = :utenteId and t.creatoIl > :da")
    long contaCreatiDopo(@Param("utenteId") UUID utenteId, @Param("da") LocalDateTime da);

    /** Pulizia pigra in un solo DELETE: via i token dell'utente creati prima di `limite` (il servizio passa un giorno fa) */
    @Modifying
    @Query("delete from TokenCambioEmail t where t.utente.id = :utenteId and t.creatoIl < :limite")
    int eliminaCreatiPrima(@Param("utenteId") UUID utenteId, @Param("limite") LocalDateTime limite);

    /** Segna come usati tutti i token ancora utilizzabili dell'utente: vale solo il link più recente, o nessuno */
    @Modifying
    @Query("update TokenCambioEmail t set t.usatoIl = :adesso where t.utente.id = :utenteId and t.usatoIl is null")
    int invalidaDellUtente(@Param("utenteId") UUID utenteId, @Param("adesso") LocalDateTime adesso);

    /** Segna il token come usato solo se non lo era: restituisce 1 alla sola richiesta che arriva per prima */
    @Modifying
    @Query("update TokenCambioEmail t set t.usatoIl = :adesso where t.id = :id and t.usatoIl is null")
    int segnaUsato(@Param("id") UUID id, @Param("adesso") LocalDateTime adesso);
}
