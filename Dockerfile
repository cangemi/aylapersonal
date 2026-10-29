FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline -DexcludeArtifactIds=spring-boot-devtools
COPY src ./src
RUN mvn -B -ntp package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre
RUN groupadd --system ayla && useradd --system --gid ayla --home-dir /app ayla
WORKDIR /app
COPY --from=build --chown=ayla:ayla /build/target/ayla-0.0.1-SNAPSHOT.jar /app/ayla.jar
USER ayla
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 9091
ENTRYPOINT ["java","-jar","/app/ayla.jar"]
