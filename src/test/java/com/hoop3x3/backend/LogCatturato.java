package com.hoop3x3.backend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Le righe di log di una classe, lette dal test invece che dalla console: l'output della build resta pulito, e il test può
 * controllare che la riga ci sia, una sola e fatta così. Si chiude a fine test per rimettere il logger com'era.
 * <p>
 * Nei test che caricano il contesto di Spring va creato in un metodo {@code @BeforeEach} e non in un campo: Spring Boot
 * riconfigura il logging quando carica il contesto, cioè dopo la creazione dell'istanza del test, e per il primo test della
 * classe si porterebbe via il raccoglitore.
 */
public final class LogCatturato implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> raccolta = new ListAppender<>();

    public LogCatturato(Class<?> classe) {
        this(classe.getName());
    }

    /** Un logger per nome: «org.springframework.web» raccoglie le righe di tutte le classi sotto quel pacchetto */
    public LogCatturato(String nomeDelLogger) {
        logger = (Logger) LoggerFactory.getLogger(nomeDelLogger);
        raccolta.start();
        logger.addAppender(raccolta);
        logger.setAdditive(false); // le righe non salgono al logger della console
    }

    /** I messaggi delle righe scritte finora, senza data, livello e nome del logger */
    public List<String> righe() {
        return raccolta.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** I livelli delle righe scritte finora, nello stesso ordine di {@link #righe()} */
    public List<Level> livelli() {
        return raccolta.list.stream().map(ILoggingEvent::getLevel).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(raccolta);
        logger.setAdditive(true);
    }
}
