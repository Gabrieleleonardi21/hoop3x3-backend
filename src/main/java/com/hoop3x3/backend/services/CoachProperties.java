package com.hoop3x3.backend.services;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proprietà coach.*: chi risponde al Coach AI.
 *
 * @param fornitore «groq» (il predefinito, anche se vuoto) o «anthropic» (COACH_FORNITORE), con la chiave del fornitore scelto:
 *                  groq.api.key o anthropic.api.key. Un altro nome spegne il Coach (503) e lo dice all'avvio
 */
@ConfigurationProperties(prefix = "coach")
public record CoachProperties(@DefaultValue("groq") String fornitore) {}
