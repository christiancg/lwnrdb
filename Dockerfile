FROM maven:3.9.16-eclipse-temurin-25-alpine AS build

COPY src /app/src
COPY pom.xml /app

WORKDIR /app
RUN mvn clean package -DskipTests

# Build a minimal custom runtime with only the modules the app needs:
#   java.base        - core
#   java.management  - heap usage budget (MemoryManagement / DatabaseStatsHelper)
#   jdk.crypto.ec    - SunEC provider; ECDHE key exchange for TLS
#   jdk.unsupported  - sun.misc.Unsafe, used by EJson's UnsafeAllocator fallback
#                      to instantiate objects without a matching constructor
RUN jlink \
        --add-modules java.base,java.management,jdk.crypto.ec,jdk.unsupported \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-9 \
        --output /jre

FROM alpine:3.21

ARG PORT=8989
ENV PORT=${PORT}

# JVM native deps (musl image links against libstdc++ / libgcc)
RUN apk add --no-cache libstdc++

ENV JAVA_HOME=/opt/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=build /jre /opt/jre
COPY --from=build /app/target/lwnrdb-1.0-SNAPSHOT.jar /app/app.jar

WORKDIR /app

COPY lwnrdb.cfg /app

VOLUME /app/db
VOLUME /app/logs

EXPOSE ${PORT}

CMD ["java", "-XX:+UseZGC", "-jar", "app.jar"]