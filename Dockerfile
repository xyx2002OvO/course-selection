FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY selection-rpc/pom.xml selection-rpc/pom.xml
COPY selection-domain/pom.xml selection-domain/pom.xml
COPY selection-web/pom.xml selection-web/pom.xml
COPY selection-gateway/pom.xml selection-gateway/pom.xml
COPY selection-rpc/src selection-rpc/src
COPY selection-domain/src selection-domain/src
COPY selection-web/src selection-web/src
COPY selection-gateway/src selection-gateway/src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV HOME=/tmp
COPY --from=build /build/selection-domain/target/selection-domain-0.1.0.jar /app/domain.jar
COPY --from=build /build/selection-web/target/selection-web-0.1.0.jar /app/web.jar
COPY --from=build /build/selection-gateway/target/selection-gateway-0.1.0.jar /app/gateway.jar
ENTRYPOINT ["sh", "-c", "exec java -jar ${APP_JAR:-/app/gateway.jar}"]
