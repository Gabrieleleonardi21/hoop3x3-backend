package com.hoop3x3.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import tools.jackson.databind.JsonNode;

/**
 * La regola di {@link IndirizzoWeb} per il blocco `video` di una tappa: un array JSON di {id, titolo, url}. Ogni url deve
 * rispettare {@link IndirizzoWebValidator#valido}; un url assente o null va bene, uno che non è un testo no. La forma del
 * blocco (array o null) la controlla JsonSupport: qui un valore che non è un array si lascia passare, così il 400 dice quello.
 * Il messaggio nomina il video che non va, con il suo numero a partire da 1.
 */
public class IndirizziWebDeiVideoValidator implements ConstraintValidator<IndirizzoWeb, JsonNode> {

    @Override
    public boolean isValid(JsonNode video, ConstraintValidatorContext contesto) {
        if (video == null || !video.isArray()) return true;
        for (int i = 0; i < video.size(); i++) {
            JsonNode url = video.get(i).path("url");
            if (url.isMissingNode() || url.isNull()) continue;
            if (url.isString() && IndirizzoWebValidator.valido(url.asString())) continue;
            contesto.disableDefaultConstraintViolation();
            contesto.buildConstraintViolationWithTemplate("il video n. " + (i + 1) + " ha un url che " + contesto.getDefaultConstraintMessageTemplate())
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
