FROM eclipse-temurin:21-jre-jammy

ARG MODULE_DIR=notification
WORKDIR /app
COPY ${MODULE_DIR}/target/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
