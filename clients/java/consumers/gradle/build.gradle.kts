plugins {
    java
}

val lwnrdbVersion: String = providers.gradleProperty("lwnrdbVersion").getOrElse("0.1.0-SNAPSHOT")

repositories {
    mavenLocal()
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    implementation(platform("io.github.christiancg:lwnrdb-client-bom:$lwnrdbVersion"))
    implementation("io.github.christiancg:lwnrdb-client")
}
