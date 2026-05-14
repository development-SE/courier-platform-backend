FROM eclipse-temurin:21-jre-jammy

ARG MODULE_DIR=logistics-service
WORKDIR /app
COPY ${MODULE_DIR}/target/*.jar app.jar

EXPOSE 8086

ENTRYPOINT ["java", "-jar", "app.jar"]
