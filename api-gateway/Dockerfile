FROM eclipse-temurin:21-jre-jammy

ARG MODULE_DIR=api-gateway
WORKDIR /app
COPY ${MODULE_DIR}/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
