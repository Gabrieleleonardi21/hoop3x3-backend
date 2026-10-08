package com.hoop3x3.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Un indirizzo web scritto dall'utente (logo, sito, Instagram, url dei video), che il frontend mette in un href o in un src:
 * vuoto o assente va bene; altrimenti deve cominciare con http:// o https://, oppure essere un percorso del sito (comincia
 * con «/», come i loghi integrati /logos/nome.svg, ma non con «//» o «/\», che il browser legge come un altro sito), ed
 * essere lungo al massimo 2048 caratteri. Così «javascript:», «data:» e gli altri schemi non entrano nel database. È la
 * stessa regola di safeUrl nel frontend, che la applica quando mostra. Il messaggio è in italiano, come gli altri errori.
 * <p>
 * Si applica a un campo di testo ({@link IndirizzoWebValidator}) e al blocco `video` di una tappa, un array JSON di
 * {id, titolo, url}, di cui controlla ogni url ({@link IndirizziWebDeiVideoValidator}).
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {IndirizzoWebValidator.class, IndirizziWebDeiVideoValidator.class})
public @interface IndirizzoWeb {

    String message() default "deve essere vuoto oppure un indirizzo che inizia con http:// o https:// (o un percorso del sito"
            + " che inizia con /), al massimo " + IndirizzoWebValidator.LUNGHEZZA_MASSIMA + " caratteri";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
