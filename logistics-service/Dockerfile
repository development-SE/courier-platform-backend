# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk-jammy AS builder

ARG MODULE_DIR=logistics-service
WORKDIR /workspace

COPY . .

RUN set -eux; \
    chmod +x "./${MODULE_DIR}/mvnw"; \
    if [ -f pom.xml ]; then \
      "./${MODULE_DIR}/mvnw" -f pom.xml -pl "${MODULE_DIR}" -am -DskipTests package; \
    elif [ -f build.gradle ] || [ -f build.gradle.kts ] || [ -f settings.gradle ] || [ -f settings.gradle.kts ]; then \
      chmod +x ./gradlew; \
      ./gradlew ":${MODULE_DIR}:bootJar" -x test; \
    else \
      echo "Unable to detect Maven or Gradle build files" >&2; \
      exit 1; \
    fi; \
    cp "$(find "${MODULE_DIR}/target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' ! -name '*-plain.jar' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar

EXPOSE 8086

ENTRYPOINT ["java", "-jar", "app.jar"]
