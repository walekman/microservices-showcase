FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY account-service/pom.xml account-service/pom.xml
COPY transfer-service/pom.xml transfer-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY fraud-service/pom.xml fraud-service/pom.xml
COPY gateway-service/pom.xml gateway-service/pom.xml
RUN chmod +x mvnw && ./mvnw -pl fraud-service -am dependency:go-offline -B
COPY fraud-service/src fraud-service/src
RUN ./mvnw -pl fraud-service -am package -DskipTests -B

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/fraud-service/target/fraud-service-*.jar app.jar
ARG GIT_SHA=unknown
ENV GIT_SHA=${GIT_SHA}
LABEL org.opencontainers.image.revision=${GIT_SHA}
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
