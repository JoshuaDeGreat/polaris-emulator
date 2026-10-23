
# Build Polaris using Java 25 and Maven 3.10
FROM maven:3.10.0-eclipse-temurin-25 AS build

WORKDIR /src
COPY . .

RUN mvn -B -ntp -Dtest=WebsiteSessionServiceTest,WebsiteAccountEndpointsTest,WebsiteStaffAccessTest,CommunityEndpointsTest,TurnstileVerifierResponseTest,PublicRoomGalleryTest,HousekeepingGalleryAccessTest,PublicRoomGalleryHttpHandlerTest,CatalogPosterDataTest,CatalogPurchaseAvailabilityTest,CatalogValidatorTest,JdbcCatalogValidationDataRepositoryTest,MariaDbMigrationBackupTest package

# Run the compiled game server
FROM eclipse-temurin:25-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends mariadb-client \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build \
  /src/target/*-jar-with-dependencies.jar \
  /app/polaris.jar

CMD ["java", "-Xms128m", "-Xmx768m", \
     "-jar", "/app/polaris.jar"]
