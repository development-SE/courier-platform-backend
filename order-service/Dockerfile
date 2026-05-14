FROM eclipse-temurin:21-jre-jammy

ARG MODULE_DIR=order-service
WORKDIR /app
COPY ${MODULE_DIR}/target/*.jar app.jar

EXPOSE 9095

ENTRYPOINT ["java", "-jar", "app.jar"]
