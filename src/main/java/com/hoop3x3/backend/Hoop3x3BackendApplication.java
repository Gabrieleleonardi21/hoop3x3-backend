package com.hoop3x3.backend;

import com.hoop3x3.backend.runners.ImportCampetti;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;
import java.util.stream.Stream;

@SpringBootApplication
public class Hoop3x3BackendApplication {
    public static void main(String[] args) {
        if (ImportCampetti.richiesto(args)) {
            // Con --importa-campetti il processo è un comando, non un server: il contesto parte lo stesso con Tomcat (filtri e
            // controller sono bean web e senza non si avvia), ma su una porta libera qualsiasi, perché su Render la porta del
            // servizio è già occupata dal server vero; appena l'import ha finito il processo termina con il suo codice d'uscita
            // (ImportCampetti è un ExitCodeGenerator: 0 se è andato bene, 1 se si è fermato)
            String[] conPortaLibera = Stream.concat(Arrays.stream(args), Stream.of("--server.port=0")).toArray(String[]::new);
            System.exit(SpringApplication.exit(SpringApplication.run(Hoop3x3BackendApplication.class, conPortaLibera)));
        }
        SpringApplication.run(Hoop3x3BackendApplication.class, args);
    }
}
