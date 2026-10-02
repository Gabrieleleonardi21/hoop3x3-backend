package com.hoop3x3.backend.services;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Groq finto per i test: un server HTTP locale (solo su 127.0.0.1, con una porta libera scelta dal sistema)
 * che registra le richieste ricevute e risponde come gli si ordina. Il servizio usa il suo vero client HTTP,
 * ma i test non escono dal computer e non servono chiavi vere.
 */
final class GroqFinto implements AutoCloseable {

    /** Ciò che è arrivato al server: i test controllano la chiave e il corpo inoltrati */
    record Richiesta(String autorizzazione, String corpo) {}

    private final HttpServer server;
    private final ExecutorService thread = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Richiesta> richieste = new CopyOnWriteArrayList<>();
    private volatile HttpHandler comportamento;

    GroqFinto() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Un thread per richiesta: un gestore che aspetta non blocca gli altri
        server.setExecutor(thread);
        server.createContext("/", scambio -> {
            String corpo = new String(scambio.getRequestBody().readAllBytes(), UTF_8);
            richieste.add(new Richiesta(scambio.getRequestHeaders().getFirst("Authorization"), corpo));
            comportamento.handle(scambio);
        });
        rispondi(200, "{\"choices\":[]}");
        server.start();
    }

    /** Indirizzo da dare al servizio al posto di quello vero di Groq */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1/chat/completions";
    }

    List<Richiesta> richieste() {
        return richieste;
    }

    /** Da ora risponde sempre con lo stato e il corpo dati, in JSON */
    void rispondi(int stato, String corpo) {
        comportamento = scambio -> {
            byte[] byteCorpo = corpo.getBytes(UTF_8);
            // Con lunghezza 0 il server userebbe la risposta a blocchi: -1 vuol dire «nessun corpo»
            long lunghezza = byteCorpo.length;
            if (lunghezza == 0) lunghezza = -1;
            scambio.getResponseHeaders().set("Content-Type", "application/json");
            scambio.sendResponseHeaders(stato, lunghezza);
            scambio.getResponseBody().write(byteCorpo);
            scambio.close();
        };
    }

    /** Da ora accetta le richieste ma non risponde: la connessione resta aperta finché il test chiude il server */
    void nonRispondeMai() {
        comportamento = scambio -> aspetta();
    }

    /** Fermo il thread del gestore: lo interrompe close(), a fine test */
    private static void aspetta() {
        try {
            Thread.sleep(Duration.ofMinutes(1));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        thread.shutdownNow(); // interrompe i gestori ancora in attesa
    }
}
