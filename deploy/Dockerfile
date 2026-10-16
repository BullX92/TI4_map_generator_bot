FROM maven:3.9.15-eclipse-temurin-26-alpine AS build
WORKDIR /build
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dspotless.skip=true -Dmaven.test.skip=true package

FROM amazoncorretto:26-alpine3.23
RUN apk add --no-cache fontconfig ttf-dejavu && addgroup -S ti4 && adduser -S -G ti4 ti4 \
    && mkdir -p /opt/STORAGE && chown ti4:ti4 /opt/STORAGE
WORKDIR /app
COPY --from=build /build/target/TI4_map_generator_discord_bot-1.0-SNAPSHOT.jar /app/tibot.jar
COPY --from=build /build/src/main/resources /opt/resources
ENV DB_PATH=/opt/STORAGE RESOURCE_PATH=/opt/resources
USER ti4
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-XX:+UseStringDeduplication", "-jar", "/app/tibot.jar"]
