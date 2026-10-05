package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.ArchivioTappa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ArchivioTappaRepository extends JpaRepository<ArchivioTappa, UUID> {

    /** Riga dell'elenco: i dati sintetici di una pubblicazione, come li restituisce {@link #elenco} */
    interface VoceElenco {
        UUID getTappaId();
        String getNome();
        String getLuogo();
        String getData();
        int getNumeroSquadre();
        String getLega();
        String getAutore();
        LocalDateTime getPubblicatoIl();
    }

    /**
     * L'elenco dell'archivio, dalla pubblicazione più recente, in una sola query. I dati della tappa si estraggono dal JSONB
     * dentro il database: il contenuto (squadre, partite, statistiche...) non viaggia e non si interpreta, e le
     * pubblicazioni non si caricano come entity. SQL nativo perché gli operatori JSONB di PostgreSQL (-> e ->>) e
     * jsonb_array_length non fanno parte di JPQL.
     * Le pubblicazioni fatte con il vecchio endpoint hanno la tappa scelta dal client e possono avere forme strane: coalesce
     * e CASE fanno di nome, luogo e data mancanti dei vuoti, e di «squadre» che non è un array 0 squadre, invece di un
     * errore che romperebbe l'elenco intero.
     */
    @Query(nativeQuery = true, value = """
            select a.tappa_id,
                   coalesce(a.contenuto ->> 'nome', '') as nome,
                   coalesce(a.contenuto ->> 'luogo', '') as luogo,
                   coalesce(a.contenuto ->> 'data', '') as data,
                   case when jsonb_typeof(a.contenuto -> 'squadre') = 'array'
                        then jsonb_array_length(a.contenuto -> 'squadre') else 0 end as numero_squadre,
                   a.lega_nome as lega,
                   u.nome as autore,
                   a.pubblicato_il
            from archivio_tappe a
            join utenti u on u.id = a.autore_id
            order by a.pubblicato_il desc
            """)
    List<VoceElenco> elenco();
}
