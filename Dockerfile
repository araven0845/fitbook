# Stage 1: build the jar with Maven on JDK 21
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Stage 2: run it on a slim JRE as a non-root user
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home appuser \
    && mkdir /data && chown appuser /data
WORKDIR /app
COPY --from=build /build/target/fitbook.jar app.jar

# SQLite lives in an embedded file; keep it on a volume-friendly path.
ENV FITBOOK_DB_PATH=/data/fitbook.db
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
