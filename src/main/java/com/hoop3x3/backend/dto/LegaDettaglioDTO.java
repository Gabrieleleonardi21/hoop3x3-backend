package com.hoop3x3.backend.dto;

import java.util.List;
import java.util.UUID;

public record LegaDettaglioDTO(UUID id, String nome, List<TappaDTO> tappe) {}
