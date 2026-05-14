FROM eclipse-temurin:21-jre-jammy

ARG MODULE_DIR=auth-service
WORKDIR /app
COPY ${MODULE_DIR}/target/*.jar app.jar

EXPOSE 9091

ENTRYPOINT ["java", "-jar", "app.jar"]
