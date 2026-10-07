package com.hoop3x3.backend.dto;

import java.util.UUID;

/**
 * PubTappa del frontend: snapshot pubblicato + lega, autore (nome) e timestamp. È la tappa per intero
 * (GET /api/archivio/{tappaId} e risposta della pubblicazione); l'elenco usa la voce sintetica VoceArchivioDTO.
 */
public record CopiaPubblicaDTO(TappaDTO tappa, String lega, String autore, UUID autoreId, long ts) {}
