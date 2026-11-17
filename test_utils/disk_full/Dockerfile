FROM eclipse-temurin:27-jre

WORKDIR /app

COPY target/lwnrdb.jar /app/app.jar

CMD ["java", "-XX:+UseG1GC", "-Xmx512m", "-Djdk.nio.maxCachedBufferSize=262144", "-jar", "app.jar"]
