plugins {
  id("org.jetbrains.kotlin.jvm") version "1.9.25"
  id("org.jetbrains.kotlin.plugin.allopen") version "1.9.25"
  id("org.jetbrains.kotlin.plugin.jpa") version "1.9.25"
  id("com.google.devtools.ksp") version "1.9.25-1.0.20"
  id("com.github.johnrengelman.shadow") version "8.1.1"
  id("io.micronaut.application") version "4.4.2"
  id("io.micronaut.aot") version "4.4.2"
}

version = "0.1"
group = "com.freesoullabs"

val kotlinVersion = project.properties.get("kotlinVersion")
repositories {
  mavenCentral()

  maven {
    name = "GitHubPackages"
    url = uri("https://maven.pkg.github.com/joaquindiez/micronaut-kool-queue")
    credentials {
      username = project.findProperty("gpr.user") as String? ?: System.getenv("GPR_USERNAME")
      password = project.findProperty("gpr.key") as String? ?: System.getenv("GPR_TOKEN")
    }
  }
  mavenLocal()
}

dependencies {
  ksp("io.micronaut.data:micronaut-data-processor")
  ksp("io.micronaut:micronaut-http-validation")
  ksp("io.micronaut.serde:micronaut-serde-processor")
  implementation("io.micronaut.data:micronaut-data-hibernate-jpa")
  implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
  implementation("io.micronaut.serde:micronaut-serde-jackson")
  implementation("io.micronaut.sql:micronaut-jdbc-hikari")
  implementation("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
  implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlinVersion}")

  implementation("joaquindiez:micronaut-kool-queue-core:0.1.3")
  compileOnly("io.micronaut:micronaut-http-client")
  runtimeOnly("ch.qos.logback:logback-classic")
  runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin")
  runtimeOnly("org.postgresql:postgresql")
  runtimeOnly("org.yaml:snakeyaml")
  testImplementation("io.micronaut:micronaut-http-client")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")
  testImplementation("org.testcontainers:testcontainers")
}


application {
  mainClass = "com.freesoullabs.ApplicationKt"
}
java {
  sourceCompatibility = JavaVersion.toVersion("17")
}


graalvmNative.toolchainDetection = false

micronaut {
  runtime("netty")
  testRuntime("junit5")
  processing {
    incremental(true)
    annotations("com.freesoullabs.*")
  }
  aot {
    // Please review carefully the optimizations enabled below
    // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
    optimizeServiceLoading = false
    convertYamlToJava = false
    precomputeOperations = true
    cacheEnvironment = true
    optimizeClassLoading = true
    deduceEnvironment = true
    optimizeNetty = true
    replaceLogbackXml = true
  }
}



