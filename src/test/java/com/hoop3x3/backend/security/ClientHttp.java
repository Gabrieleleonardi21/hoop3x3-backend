package com.hoop3x3.backend.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un client HTTP per i test sul server vero (Tomcat su 127.0.0.1): manda una POST in JSON e restituisce la risposta intera,
 * con le intestazioni. Il percorso va com'è, senza che nessuno lo ricodifichi: serve a provare anche «/api/auth/%6Cogin».
 */
final class ClientHttp {

    private final HttpClient http = HttpClient.newHttpClient();
    private final int porta;

    ClientHttp(int porta) {
        this.porta = porta;
    }

    /** `intestazioni` sono coppie nome, valore */
    HttpResponse<String> post(String percorso, String corpo, String... intestazioni) throws Exception {
        HttpRequest.Builder richiesta = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + percorso))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        for (int i = 0; i < intestazioni.length; i += 2) {
            richiesta.header(intestazioni[i], intestazioni[i + 1]);
        }
        return http.send(richiesta.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 429 con il corpo di tutti gli errori ({message, timestamp}, niente altro), il Retry-After in secondi e il messaggio */
    static void assert429(HttpResponse<String> risposta, long secondi, String messaggio) {
        assertThat(risposta.statusCode()).isEqualTo(429);
        assertThat(risposta.headers().firstValue("Retry-After")).contains(String.valueOf(secondi));
        assertThat(risposta.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        JsonNode corpo = JsonMapper.builder().build().readTree(risposta.body());
        assertThat(corpo.propertyNames()).containsExactlyInAnyOrder("message", "timestamp");
        assertThat(corpo.get("message").asString()).isEqualTo(messaggio);
        assertThat(LocalDateTime.parse(corpo.get("timestamp").asString())).isNotNull();
    }
}
