FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:17-jre
WORKDIR /app

# curl is only used by docker-compose's HEALTHCHECK against the management port.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring

COPY --from=build /app/target/shopping-backend-1.0.0.jar app.jar
RUN mkdir -p /app/uploads && chown -R spring:spring /app

USER spring:spring

EXPOSE 8080 8081
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
