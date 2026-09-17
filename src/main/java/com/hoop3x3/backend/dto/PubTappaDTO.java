package com.hoop3x3.backend.dto;

import java.util.UUID;

/** PubTappa del frontend: snapshot pubblicato + lega, autore (nome) e timestamp */
public record PubTappaDTO(TappaDTO tappa, String lega, String autore, UUID autoreId, long ts) {}
