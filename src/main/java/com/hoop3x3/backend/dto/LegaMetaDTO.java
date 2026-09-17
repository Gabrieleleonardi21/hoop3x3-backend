package com.hoop3x3.backend.dto;

import java.util.UUID;

/** Voce dell'indice leghe: `ts` in millisecondi epoch, come il LegaMeta del frontend */
public record LegaMetaDTO(UUID id, String nome, long ts, int nTappe) {}
