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
     * L'elenco dell'archivio, dalla pubblicazione più recente, in una sola query sulle colonne della tabella (nome, luogo,
     * data e numero di squadre le scrive la pubblicazione, migrazione V6): il contenuto JSONB, cioè la tappa intera, non si
     * legge né si decomprime, e le pubblicazioni non si caricano come entity.
     */
    @Query("""
            select a.tappaId as tappaId, a.nome as nome, a.luogo as luogo, a.data as data,
                   a.numeroSquadre as numeroSquadre, a.legaNome as lega, a.autore.nome as autore,
                   a.pubblicatoIl as pubblicatoIl
            from ArchivioTappa a
            order by a.pubblicatoIl desc
            """)
    List<VoceElenco> elenco();

    /**
     * Tutte le pubblicazioni intere, dalla più vecchia: il `join fetch` evita una query per autore quando si costruisce la copia
     * pubblica (il nome dell'autore), e l'ordine dal più vecchio conta perché il frontend mostra la grafia dell'ultima tappa
     * dell'elenco. A parità di data decide l'id, così l'ordine non cambia da una richiesta all'altra
     */
    @Query("select a from ArchivioTappa a join fetch a.autore order by a.pubblicatoIl asc, a.tappaId asc")
    List<ArchivioTappa> tutteConAutore();
}
