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
USER hoop
# Heap al massimo al 60% della RAM del container (il piano free di Render ne ha 512 MB, quindi circa 300 MB): il resto serve a
# metaspace, thread, buffer e codice compilato, che non stanno nell'heap. Con il 75% (384 MB) il processo intero superava i
# 512 MB e Render lo uccideva (OOM-kill) senza una riga nei log. ExitOnOutOfMemoryError: un OutOfMemoryError non ferma la
# JVM da solo, che resterebbe viva e ferma senza rispondere; così esce subito e Render la riavvia
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
# Porta di default; su Render vale quella della variabile PORT (server.port=${PORT:3001})
EXPOSE 3001
ENTRYPOINT ["java", "-jar", "app.jar"]
