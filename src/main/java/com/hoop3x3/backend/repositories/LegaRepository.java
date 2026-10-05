package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.Lega;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface LegaRepository extends JpaRepository<Lega, UUID> {

    /** Riga dell'indice leghe: i dati di una lega e il numero delle sue tappe, contate dal database */
    interface VoceIndice {
        UUID getId();
        String getNome();
        LocalDateTime getModificatoIl();
        int getNumeroTappe();
    }

    /**
     * L'indice delle leghe di un utente, dalla più recente, in una sola query. Le tappe si contano e non si caricano
     * (ognuna ha cinque colonne JSONB): il left join tiene nell'elenco anche una lega senza tappe, con 0.
     */
    @Query("""
            select l.id as id, l.nome as nome, l.modificatoIl as modificatoIl, count(t.id) as numeroTappe
            from Lega l left join l.tappe t
            where l.owner.id = :ownerId
            group by l.id, l.nome, l.modificatoIl
            order by l.modificatoIl desc
            """)
    List<VoceIndice> indiceDi(@Param("ownerId") UUID ownerId);
}
