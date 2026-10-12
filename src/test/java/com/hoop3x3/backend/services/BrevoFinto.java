package com.hoop3x3.backend.services;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Brevo finto per i test, come NominatimFinto: un server HTTP locale (solo 127.0.0.1, porta libera) che registra metodo,
 * intestazioni e corpo di ogni richiesta all'API delle email, e risponde con lo stato e il corpo che gli si danno, anche in
 * ritardo (per il timeout). Il client usa il suo vero client HTTP.
 */
final class BrevoFinto implements AutoCloseable {

    /** Ciò che è arrivato: il metodo, le tre intestazioni che Brevo legge e il corpo JSON */
    record Richiesta(String metodo, String apiKey, String accept, String contentType, String corpo) {}

    private final HttpServer server;
    private final int porta;
    private final ExecutorService esecutore = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Richiesta> richieste = new CopyOnWriteArrayList<>();
    private volatile int stato = 201;
    private volatile byte[] corpo = "{\"messageId\":\"<prova@smtp-relay.brevo.com>\"}".getBytes(UTF_8);
    private volatile long ritardoMillis;
    private boolean chiuso;

    BrevoFinto() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(esecutore);
        // Solo il percorso dell'API delle email: altre richieste su questa porta ricevono 404 e non entrano nell'elenco
        server.createContext("/v3/smtp/email", scambio -> {
            richieste.add(new Richiesta(scambio.getRequestMethod(), scambio.getRequestHeaders().getFirst("api-key"),
                    scambio.getRequestHeaders().getFirst("Accept"), scambio.getRequestHeaders().getFirst("Content-Type"),
                    new String(scambio.getRequestBody().readAllBytes(), UTF_8)));
            try {
                Thread.sleep(ritardoMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scambio.getResponseHeaders().set("Content-Type", "application/json");
            byte[] da = corpo;
            scambio.sendResponseHeaders(stato, da.length);
            scambio.getResponseBody().write(da);
            scambio.close();
        });
        server.start();
        porta = server.getAddress().getPort();
    }

    /** L'indirizzo da dare al client al posto di quello vero di Brevo */
    String url() {
        return "http://127.0.0.1:" + porta + "/v3/smtp/email";
    }

    List<Richiesta> richieste() {
        return richieste;
    }

    /** Da ora risponde così a ogni richiesta */
    void risponde(int stato, String corpo) {
        this.stato = stato;
        this.corpo = corpo.getBytes(UTF_8);
    }

    /** Da ora aspetta tanto prima di rispondere */
    void rispondeDopo(long millis) {
        this.ritardoMillis = millis;
    }

    /** Ferma il server: le richieste successive falliscono per la rete. Chiamarlo più volte non fa danni */
    @Override
    public void close() {
        if (chiuso) return;
        chiuso = true;
        server.stop(0);
        esecutore.close();
    }
}
