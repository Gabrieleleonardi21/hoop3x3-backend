package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.Campetto;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CampettoRepository extends JpaRepository<Campetto, UUID> {

    /**
     * I campetti dentro un riquadro di coordinate: è il ritaglio grossolano della ricerca per raggio, che usa l'indice
     * (lat, lng); la distanza vera la calcola CampettoService con Haversine. L'autore arriva con la stessa query: il DTO ne
     * legge nome e id per ogni campetto, e a richiesta sarebbe una query per autore
     */
    @Query("select c from Campetto c left join fetch c.autore "
            + "where c.lat between :latMin and :latMax and c.lng between :lngMin and :lngMax")
    List<Campetto> nelRiquadro(double latMin, double latMax, double lngMin, double lngMax);

    /**
     * I campetti il cui nome o la cui città contiene il testo, senza distinzione di maiuscole (ilike), ordinati per città e
     * nome. `filtro` è già nella forma di un like («%dora%», con i caratteri speciali protetti da «\»: CampettoService)
     */
    @Query("select c from Campetto c left join fetch c.autore "
            + "where c.nome ilike :filtro escape '\\' or c.citta ilike :filtro escape '\\' order by c.citta, c.nome")
    List<Campetto> perTesto(String filtro, Limit limite);

    /** I campetti già importati da una fonte con quegli id nella fonte (indice unico della V8): li usa ImportCampetti */
    List<Campetto> findByFonteAndFonteIdIn(String fonte, Collection<String> fonteId);
}
