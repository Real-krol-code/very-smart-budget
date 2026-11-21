FROM gradle:9-jdk25 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
COPY src src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
