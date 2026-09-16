FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY account-service/pom.xml account-service/pom.xml
COPY transfer-service/pom.xml transfer-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY fraud-service/pom.xml fraud-service/pom.xml
RUN chmod +x mvnw && ./mvnw -pl transfer-service -am dependency:go-offline -B
COPY transfer-service/src transfer-service/src
RUN ./mvnw -pl transfer-service -am package -DskipTests -B

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/transfer-service/target/transfer-service-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
