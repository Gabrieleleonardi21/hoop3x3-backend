package com.hoop3x3.backend.services;

import java.io.IOException;

/**
 * Anthropic finto per i test: lo stesso server locale di GroqFinto, con l'indirizzo della Messages API. La chiave arriva
 * nell'intestazione x-api-key e non in Authorization: i test la leggono con {@code richiesta.intestazione("x-api-key")}.
 */
final class AnthropicFinto extends GroqFinto {

    AnthropicFinto() throws IOException {
        super();
    }

    /** Indirizzo da dare al servizio al posto di quello vero di Anthropic */
    @Override
    String url() {
        return super.url().replace("/openai/v1/chat/completions", "/v1/messages");
    }
}
