package com.hoop3x3.backend;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test di integrazione con il database di prova: contesto Spring completo, profilo «test»
 * (src/test/resources/application-test.properties) e tabelle svuotate prima di ogni test.
 * Le classi che la usano hanno il suffisso IT: le esegue solo ./mvnw verify -Pintegrazione.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
// Il database lo scelgono solo le variabili TEST_DB_*: le proprietà del test prevalgono anche su
// SPRING_DATASOURCE_* esportate nel terminale, che porterebbero i test su un database non di prova
@SpringBootTest(properties = {
        "spring.datasource.url=${TEST_DB_URL:" + TestDiIntegrazione.URL_PREDEFINITO + "}",
        "spring.datasource.username=${TEST_DB_USERNAME:${DB_USERNAME:postgres}}",
        "spring.datasource.password=${TEST_DB_PASSWORD:${DB_PASSWORD:}}"})
@ActiveProfiles("test")
// svuota.sql contiene un blocco DO con dei «;»: si esegue come un'unica istruzione, senza dividerlo
@Sql(scripts = "classpath:svuota.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD,
        config = @SqlConfig(separator = ScriptUtils.EOF_STATEMENT_SEPARATOR))
public @interface TestDiIntegrazione {

    /** Database di prova usato quando TEST_DB_URL non è impostata (da creare con createdb hoop3x3_test) */
    String URL_PREDEFINITO = "jdbc:postgresql://localhost:5432/hoop3x3_test";
}
