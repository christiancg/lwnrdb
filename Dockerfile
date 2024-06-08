FROM maven:3.9.7-eclipse-temurin-21-alpine AS build

COPY src /app/src
COPY pom.xml /app

WORKDIR /app
RUN mvn clean install -U

FROM alpine:3.20
COPY --from=build /app/target/lwnrdb-1.0-SNAPSHOT.jar /app/app.jar

RUN apk add openjdk21-jre-headless

WORKDIR /app

COPY lwnrdb.cfg /app

VOLUME /app/db
VOLUME /app/logs

EXPOSE 8989

CMD ["java", "-jar", "app.jar"]