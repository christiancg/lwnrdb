# syntax=docker/dockerfile:1

# ---- Build stage: GraalVM CE + Maven → native executable ----
# native-image-community ships GraalVM, the native-image tool, and the C
# toolchain (gcc + zlib headers) required to build native executables.
FROM ghcr.io/graalvm/native-image-community:25 AS build

# Maven isn't bundled in the GraalVM images; install a pinned version. Try the
# fast CDN mirror first, then fall back to the permanent archive (which keeps
# every release, so the pin never disappears). Maven runs on the image's GraalVM
# JDK (JAVA_HOME), so the native-maven-plugin's compile-no-fork goal finds
# native-image automatically.
ARG MAVEN_VERSION=3.9.16
ARG REVISION=1.0-SNAPSHOT
RUN curl -fsSL "https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/maven.tar.gz \
 || curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/maven.tar.gz \
 && tar -xzf /tmp/maven.tar.gz -C /opt \
 && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn \
 && rm /tmp/maven.tar.gz

WORKDIR /app
COPY pom.xml .
COPY src ./src

# Build the native binary (target/lwnrdb). The BuildKit cache mount persists the
# Maven repository across builds so plugin/dependency downloads don't repeat.
# The reflection metadata under src/main/resources/META-INF/native-image is
# picked up automatically by native-image.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -Pnative package -DskipTests -Drevision=${REVISION}

# The native binary dynamically links libz (zlib); everything else it needs
# (glibc + loader) ships in the distroless base. Dereference the versioned
# library into a stable name so the runtime COPY doesn't depend on the exact
# zlib build in the base image.
RUN cp -L /lib64/libz.so.1 /tmp/libz.so.1 \
 && mkdir -p /out/app/db /out/app/logs /out/app/certs \
 && cp target/lwnrdb /out/app/lwnrdb

# ---- Runtime stage: minimal glibc base ----
# distroless/base provides glibc + the loader, and its debug variant adds only
# busybox, which runs docker-entrypoint.sh. It does not ship zlib, so the
# one extra shared library the native binary needs (libz.so.1) is copied in from
# the build stage below. (Alpine/musl is intentionally not used here: a glibc
# native image cannot run against musl; that would require a static musl build
# with its own toolchain.)
FROM gcr.io/distroless/base-debian12:debug-nonroot

ARG PORT=8989
ENV PORT=${PORT}
# Where the copied libz.so.1 lives; ensure the loader searches it.
ENV LD_LIBRARY_PATH=/usr/lib

COPY --from=build /tmp/libz.so.1 /usr/lib/libz.so.1
COPY --from=build --chown=nonroot:nonroot /out/app /app
COPY --chown=nonroot:nonroot lwnrdb.cfg /app/
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh

WORKDIR /app

USER nonroot

VOLUME /app/db
VOLUME /app/logs

EXPOSE ${PORT}

ENTRYPOINT ["/busybox/sh", "/app/docker-entrypoint.sh"]
CMD ["/app/lwnrdb"]
