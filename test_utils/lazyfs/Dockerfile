FROM eclipse-temurin:27-jdk AS lazyfs

ARG LAZYFS_TAG=0.3.1
ENV CMAKE_POLICY_VERSION_MINIMUM=3.5
RUN apt-get update && apt-get install -y --no-install-recommends git g++ cmake make pkg-config libfuse3-dev fuse3 \
    ca-certificates && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch ${LAZYFS_TAG} https://github.com/dsrhaslab/lazyfs /opt/lazyfs-src
WORKDIR /opt/lazyfs-src/libs/libpcache
RUN ./build.sh
WORKDIR /opt/lazyfs-src/lazyfs
RUN ./build.sh

FROM eclipse-temurin:27-jdk

RUN apt-get update && apt-get install -y --no-install-recommends fuse3 && rm -rf /var/lib/apt/lists/*
COPY --from=lazyfs /opt/lazyfs-src/lazyfs/build/lazyfs /usr/local/bin/lazyfs
COPY --from=lazyfs /opt/lazyfs-src/libs/libpcache/build/libpcache.so* /usr/local/lib/
RUN ldconfig
COPY test_utils/lazyfs/lazyfs.toml /app/lazyfs.toml
COPY target/lwnrdb.jar /app/app.jar
WORKDIR /app
CMD ["sleep", "infinity"]
