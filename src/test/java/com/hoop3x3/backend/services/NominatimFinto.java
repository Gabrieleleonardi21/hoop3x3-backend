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
 * Nominatim finto per i test: un server HTTP locale (solo 127.0.0.1, porta libera) che registra query, intestazioni e istante di
 * ogni richiesta, e risponde con lo stato, il tipo e il corpo che gli si danno. Il servizio usa il suo vero client HTTP.
 */
final class NominatimFinto implements AutoCloseable {

    /** Ciò che è arrivato: la query così com'è, lo User-Agent, l'Accept e il momento (nanosecondi) dell'arrivo */
    record Richiesta(String query, String userAgent, String accept, long istante) {}

    private final HttpServer server;
    private final int porta;
    private final ExecutorService esecutore = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Richiesta> richieste = new CopyOnWriteArrayList<>();
    private volatile int stato = 200;
    private volatile String tipo = "application/json";
    private volatile byte[] corpo = "[]".getBytes(UTF_8);
    private boolean chiuso;

    NominatimFinto() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(esecutore);
        // Solo il percorso della ricerca: altre richieste che capitano su questa porta (un altro programma locale) ricevono 404
        // e non entrano nell'elenco delle richieste dei test
        server.createContext("/search", scambio -> {
            richieste.add(new Richiesta(scambio.getRequestURI().getRawQuery(),
                    scambio.getRequestHeaders().getFirst("User-Agent"), scambio.getRequestHeaders().getFirst("Accept"),
                    System.nanoTime()));
            scambio.getResponseHeaders().set("Content-Type", tipo);
            byte[] da = corpo;
            scambio.sendResponseHeaders(stato, da.length == 0 ? -1 : da.length);
            if (da.length > 0) scambio.getResponseBody().write(da);
            scambio.close();
        });
        server.start();
        porta = server.getAddress().getPort();
    }

    /** L'indirizzo da dare al servizio al posto di quello vero di Nominatim */
    String url() {
        return "http://127.0.0.1:" + porta + "/search";
    }

    List<Richiesta> richieste() {
        return richieste;
    }

    /** Da ora risponde così a ogni richiesta */
    void risponde(int stato, String tipo, String corpo) {
        this.stato = stato;
        this.tipo = tipo;
        this.corpo = corpo.getBytes(UTF_8);
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
