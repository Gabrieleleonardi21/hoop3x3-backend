package com.hoop3x3.backend.dto;

import java.util.UUID;

/**
 * Voce dell'elenco dell'archivio (GET /api/archivio): ciò che l'elenco mostra di una tappa pubblicata, più l'id per
 * aprirla. Non porta il contenuto della tappa, che si legge con GET /api/archivio/{tappaId} (PubTappaDTO), e nemmeno
 * l'id dell'autore, che l'elenco non usa: `autore` è il suo nome visualizzato. `nSquadre` è il numero delle squadre
 * iscritte alla tappa, `ts` i millisecondi epoch della pubblicazione (l'elenco va dalla più recente).
 */
public record PubTappaMetaDTO(
        UUID tappaId, String nome, String luogo, String data, int nSquadre, String lega, String autore, long ts
) {}
