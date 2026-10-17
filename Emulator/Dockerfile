
# Build Polaris using Java 25 and Maven 3.10
FROM maven:3.10.0-eclipse-temurin-25 AS build

WORKDIR /src
COPY . .

RUN mvn -B -ntp -DskipTests package

# Run the compiled game server
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build \
  /src/target/*-jar-with-dependencies.jar \
  /app/polaris.jar

CMD ["java", "-Xms128m", "-Xmx768m", \
     "-jar", "/app/polaris.jar"]
