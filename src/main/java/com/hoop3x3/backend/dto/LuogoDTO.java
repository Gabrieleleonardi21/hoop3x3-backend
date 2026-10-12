package com.hoop3x3.backend.dto;

/** Un luogo trovato da Nominatim: il nome per il titolo (il primo pezzo del nome completo) e il punto in gradi */
public record LuogoDTO(String nome, double lat, double lng) {}
