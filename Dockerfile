# Immagine del backend per Render (o qualsiasi hosting di container). Due fasi: la prima compila il jar con il
# Maven Wrapper, la seconda tiene solo il JRE e il jar, così l'immagine finale è più piccola e senza sorgenti.

# ── Compilazione ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# Prima solo wrapper e pom: le dipendenze restano in cache finché il pom non cambia
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# I test girano già in CI (GitHub Actions): qui si costruisce solo il jar
RUN ./mvnw -B -q package -DskipTests

# ── Esecuzione ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre
WORKDIR /app
# Utente senza privilegi: il processo Java non gira come root
RUN groupadd --system hoop && useradd --system --gid hoop hoop
COPY --from=build /app/target/hoop-3x3-backend-*.jar app.jar
# Letto all'avvio quando DB_INIT_MODE=always (spring.sql.init.schema-locations=file:db/schema.sql)
COPY db/schema.sql db/schema.sql
USER hoop
# La JVM usa al massimo il 75% della RAM del container (il piano free di Render ne ha 512 MB)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# Porta di default; su Render vale quella della variabile PORT (server.port=${PORT:3001})
EXPOSE 3001
ENTRYPOINT ["java", "-jar", "app.jar"]
