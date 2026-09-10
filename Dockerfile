FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/course-selection-0.1.0.jar app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
