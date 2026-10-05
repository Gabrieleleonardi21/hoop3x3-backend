package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.Tappa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface TappaRepository extends JpaRepository<Tappa, UUID> {
    int countByLegaId(UUID legaId);

    /**
     * La posizione per la prossima tappa di una lega: una più della massima in uso, 0 se la lega non ha tappe. Non è il
     * numero delle tappe: dopo un'eliminazione quel numero è la posizione di una tappa che c'è ancora (BE-10).
     * La calcola il database: le tappe, con i loro blocchi JSONB, non si caricano.
     */
    @Query("select coalesce(max(t.posizione), -1) + 1 from Tappa t where t.lega.id = :legaId")
    int prossimaPosizione(@Param("legaId") UUID legaId);
}
