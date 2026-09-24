FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml . && mvn -q dependency:go-offline
COPY src src
RUN mvn -q package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/backend-1.0.0.jar app.jar
RUN useradd --system app && mkdir /data/private && chown app:app /data/private
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
