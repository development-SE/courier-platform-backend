FROM eclipse-temurin:21-jre-jammy

ARG MODULE_DIR=companyservice
WORKDIR /app
COPY ${MODULE_DIR}/target/*.jar app.jar

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "app.jar"]
