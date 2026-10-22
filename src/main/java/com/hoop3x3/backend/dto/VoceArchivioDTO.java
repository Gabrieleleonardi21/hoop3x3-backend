package com.hoop3x3.backend.dto;

import java.util.UUID;

/**
 * Voce dell'elenco dell'archivio (GET /api/archivio, il tipo `PubTappaMeta` del frontend): ciò che l'elenco mostra di una
 * tappa pubblicata, più l'id per aprirla. Non porta il contenuto della tappa, che si legge con GET /api/archivio/{tappaId}
 * (CopiaPubblicaDTO). `autore` è il nome visualizzato dell'autore, `nSquadre` il numero delle squadre iscritte alla tappa, `ts`
 * i millisecondi epoch della pubblicazione (l'elenco va dalla più recente). `legaId` (l'id della lega della tappa, null solo per
 * una pubblicazione di prima della V2 rimasta senza tappa) e `autoreId` servono al frontend per raggruppare l'elenco per lega
 * e mostrare «Ritira» all'autore (fase 12).
 */
public record VoceArchivioDTO(
        UUID tappaId, String nome, String luogo, String data, int nSquadre, String lega, String autore, long ts,
        UUID legaId, UUID autoreId
) {}
