# syntax=docker/dockerfile:1

# ---- build: compile and test nothing here (CI runs the tests), just package the API
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY engine/pom.xml engine/pom.xml
COPY api/pom.xml api/pom.xml
# The parent pom lists every module, so each pom must exist even if only the API is built.
COPY cli/pom.xml cli/pom.xml
RUN ./mvnw -B -ntp -q dependency:go-offline -pl api -am || true
COPY engine/src engine/src
COPY api/src api/src
RUN ./mvnw -B -ntp -q package -pl api -am -DskipTests -Djacoco.skip=true \
    && cp api/target/schemaguard-api-*.jar /app.jar

# ---- run: a JRE only, as a user without privileges
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home schemaguard
COPY --from=build /app.jar /app/app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD ["/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
