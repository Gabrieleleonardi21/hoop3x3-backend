package com.hoop3x3.backend;

import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La tabella dei tetti (CampiDiTesto) deve essere completa: un campo di testo con un @Size che non vi compare non avrebbe né il
 * confronto con la colonna (LimitiColonneIT) né la prova del 400 (ValidazioneWebTest).
 */
class CampiDiTestoTest {

    private static final String PACCHETTO_DEI_DTO = "com.hoop3x3.backend.dto";

    // Ogni campo di testo con un tetto in un DTO dell'applicazione sta nella tabella
    @Test
    void ogniCampoDiTestoConUnTettoStaNellaTabella() throws Exception {
        List<String> trovati = new ArrayList<>();
        for (Class<?> dto : dtoDellApplicazione()) {
            for (RecordComponent componente : dto.getRecordComponents()) {
                boolean haUnTetto = Arrays.stream(componente.getAccessor().getAnnotationsByType(Size.class))
                        .anyMatch(size -> size.max() < Integer.MAX_VALUE);
                if (componente.getType() == String.class && haUnTetto) {
                    trovati.add(dto.getSimpleName() + "." + componente.getName());
                }
            }
        }

        assertThat(CampiDiTesto.TUTTI).extracting(CampiDiTesto.Campo::toString).containsExactlyInAnyOrderElementsOf(trovati);
    }

    // Ogni voce della tabella nomina un campo che esiste e ha un tetto (tetto() lo verifica)
    @Test
    void ogniVoceDellaTabellaHaUnTetto() {
        assertThat(CampiDiTesto.TUTTI).allSatisfy(campo -> assertThat(campo.tetto()).isPositive());
    }

    /**
     * Tutti i record del pacchetto dei DTO, trovati tra i file compilati: un DTO nuovo entra da solo. «classpath*:» cerca in
     * tutte le cartelle di classi: il pacchetto esiste anche tra i test (AnagrafeDTOTest) e senza l'asterisco si fermerebbe lì.
     */
    private static List<Class<?>> dtoDellApplicazione() throws IOException, ClassNotFoundException {
        List<Class<?>> dto = new ArrayList<>();
        String percorso = "classpath*:" + PACCHETTO_DEI_DTO.replace('.', '/') + "/*.class";
        for (Resource file : new PathMatchingResourcePatternResolver().getResources(percorso)) {
            String nome = file.getFilename().replace(".class", "");
            Class<?> classe = Class.forName(PACCHETTO_DEI_DTO + "." + nome);
            if (classe.isRecord()) dto.add(classe);
        }
        return dto;
    }
}
