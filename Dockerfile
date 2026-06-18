FROM maven:3.9.16-eclipse-temurin-25-alpine AS build

COPY src /app/src
COPY pom.xml /app

WORKDIR /app
RUN mvn clean package -DskipTests

FROM eclipse-temurin:25-jre-alpine

ARG PORT=8989

ENV PORT=${PORT}

COPY --from=build /app/target/lwnrdb-1.0-SNAPSHOT.jar /app/app.jar

WORKDIR /app

COPY lwnrdb.cfg /app

VOLUME /app/db
VOLUME /app/logs

EXPOSE ${PORT}

CMD ["java", "-jar", "app.jar"]