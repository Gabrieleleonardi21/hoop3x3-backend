package com.hoop3x3.backend.security;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Proprietà limite.*: quante richieste si accettano in ogni finestra (vedi LimiteRichieste e il README). Valgono i valori di
 * produzione se mancano, e sono validate all'avvio: con 0 ogni richiesta verrebbe respinta e l'app resterebbe chiusa a
 * tutti, quindi il server non parte e dice quale proprietà è sbagliata.
 *
 * @param authAlMinuto   login, registrazione, rinnovo del token e richiesta del link di recupero password: ognuno ha il suo
 *                       contatore, per indirizzo IP. Lo stesso numero vale, per utente, per i tre endpoint del profilo che
 *                       chiedono la password attuale (cambio della password e dell'email, eliminazione), con un contatore solo
 * @param coachAlMinuto  Coach AI, per utente
 * @param coachAlGiorno  Coach AI, per utente: il giorno finisce a mezzanotte UTC
 * @param foglioAlMinuto foto dei fogli lette (POST /api/coach/foglio), per utente, con contatori separati da quelli del Coach
 * @param foglioAlGiorno foto dei fogli lette, per utente: il giorno finisce a mezzanotte UTC
 */
@ConfigurationProperties(prefix = "limite")
@Validated
public record LimiteRichiesteProperties(
        @DefaultValue("10") @Min(value = 1, message = "limite.auth-al-minuto deve essere almeno 1") int authAlMinuto,
        @DefaultValue("20") @Min(value = 1, message = "limite.coach-al-minuto deve essere almeno 1") int coachAlMinuto,
        @DefaultValue("300") @Min(value = 1, message = "limite.coach-al-giorno deve essere almeno 1") int coachAlGiorno,
        @DefaultValue("5") @Min(value = 1, message = "limite.foglio-al-minuto deve essere almeno 1") int foglioAlMinuto,
        @DefaultValue("30") @Min(value = 1, message = "limite.foglio-al-giorno deve essere almeno 1") int foglioAlGiorno
) {}
