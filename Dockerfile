FROM maven:3.10.0-eclipse-temurin-27-alpine AS build

ARG REVISION=1.0-SNAPSHOT

COPY src /app/src
COPY pom.xml /app

WORKDIR /app
RUN mvn clean package -DskipTests -Drevision=${REVISION}

RUN jlink \
        --add-modules java.base,java.management,java.net.http,jdk.crypto.ec,jdk.unsupported \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-9 \
        --output /jre

FROM alpine:3.21

ARG PORT=8989
ENV PORT=${PORT}

RUN apk add --no-cache libstdc++

ENV JAVA_HOME=/opt/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=build /jre /opt/jre
COPY --from=build /app/target/lwnrdb.jar /app/app.jar

WORKDIR /app

COPY lwnrdb.cfg /app
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh

RUN addgroup -S lwnrdb && adduser -S -G lwnrdb lwnrdb \
    && mkdir -p /app/db /app/logs /app/certs \
    && chown -R lwnrdb:lwnrdb /app

USER lwnrdb

VOLUME /app/db
VOLUME /app/logs

EXPOSE ${PORT}

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["java", "-XX:+UseZGC", "-jar", "app.jar"]