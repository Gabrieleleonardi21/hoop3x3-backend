package com.hoop3x3.backend.services;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Static Maps finto per i test: un server HTTP locale (solo 127.0.0.1, porta libera scelta dal sistema) che registra la
 * query di ogni richiesta e risponde con lo stato, il tipo e il corpo che gli si danno. Il servizio usa il suo vero client
 * HTTP, ma i test non escono dal computer e non servono chiavi vere.
 */
final class GoogleFinto implements AutoCloseable {

    /** Un PNG minimo: la firma del formato e poco altro, basta a distinguerlo dal testo */
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};

    /** La query della richiesta così come è arrivata (center, zoom, size, scale, key...) */
    record Richiesta(String query) {}

    private final HttpServer server;
    private final int porta;
    private final ExecutorService esecutore = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Richiesta> richieste = new CopyOnWriteArrayList<>();
    private volatile int stato = 200;
    private volatile String tipo = "image/png";
    private volatile byte[] corpo = PNG;
    private boolean chiuso;

    GoogleFinto() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(esecutore);
        server.createContext("/", scambio -> {
            richieste.add(new Richiesta(scambio.getRequestURI().getRawQuery()));
            scambio.getResponseHeaders().set("Content-Type", tipo);
            // Con lunghezza 0 il server userebbe la risposta a blocchi: -1 vuol dire «nessun corpo»
            byte[] da = corpo;
            scambio.sendResponseHeaders(stato, da.length == 0 ? -1 : da.length);
            if (da.length > 0) scambio.getResponseBody().write(da);
            scambio.close();
        });
        server.start();
        porta = server.getAddress().getPort();
    }

    /** L'indirizzo da dare al servizio al posto di quello vero di Google */
    String url() {
        return "http://127.0.0.1:" + porta + "/maps/api/staticmap";
    }

    List<Richiesta> richieste() {
        return richieste;
    }

    /** Da ora risponde così a ogni richiesta */
    void risponde(int stato, String tipo, byte[] corpo) {
        this.stato = stato;
        this.tipo = tipo;
        this.corpo = corpo;
    }

    /** Ferma il server: le richieste successive falliscono con un errore di rete. Chiamarlo più volte non fa danni */
    @Override
    public void close() {
        if (chiuso) return;
        chiuso = true;
        server.stop(0);
        esecutore.close();
    }
}
