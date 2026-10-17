# Debian/Ubuntu-based Temurin images: the alpine variants have no arm64 build.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src ./src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
# Never run as root.
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build --chown=app:app /app/target/lotbazaar-service.jar app.jar
USER app
EXPOSE 8080
# Configure with DB_URL, DB_USER, DB_PASSWORD and JWT_SECRET (see README).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
