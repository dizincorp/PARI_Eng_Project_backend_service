# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
# Tests run in CI before the image is built; repository tests need Docker, which is not available here.
RUN ./gradlew --no-daemon bootJar -x test && mv build/libs/*[!n].jar app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home pari
USER pari
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
