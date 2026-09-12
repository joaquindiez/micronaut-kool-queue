plugins {
  id("org.jetbrains.kotlin.jvm") version "2.3.21"
  id("org.jetbrains.kotlin.plugin.allopen") version "2.3.21"
  id("org.jetbrains.kotlin.plugin.jpa") version "2.3.21"
  id("com.google.devtools.ksp") version "2.3.10"
  id("com.gradleup.shadow") version "9.6.1"
  id("io.micronaut.application") version "5.0.2"
  id("io.micronaut.aot") version "5.0.2"
}

version = "0.1"
group = "com.freesoullabs"

val kotlinVersion = project.findProperty("kotlinVersion")
val testcontainersVersion = "2.0.5"
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


  implementation(project(":micronaut-kool-queue-core"))


  compileOnly("io.micronaut:micronaut-http-client")
  runtimeOnly("ch.qos.logback:logback-classic")
  runtimeOnly("tools.jackson.module:jackson-module-kotlin")
  runtimeOnly("org.postgresql:postgresql")
  runtimeOnly("org.yaml:snakeyaml")
  testImplementation("io.micronaut:micronaut-http-client")
  // Micronaut 5 only version-manages testcontainers-bom, so import it as a platform.
  // Testcontainers 2.x also renamed every module with a "testcontainers-" prefix.
  testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testImplementation("org.testcontainers:testcontainers-postgresql")
  testImplementation("org.testcontainers:testcontainers")
}


application {
  mainClass = "com.freesoullabs.ApplicationKt"
}
java {
  sourceCompatibility = JavaVersion.toVersion("25")
}

kotlin {
  jvmToolchain(25)
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
  }
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




// The benchmark is a JUnit test so it can boot the real application context,
// but it needs a live PostgreSQL and takes minutes, so `test` skips it and a
// dedicated task runs it. See docs/benchmark.md.
tasks.named<Test>("test") {
  useJUnitPlatform {
    excludeTags("benchmark")
  }
}

tasks.register<Test>("benchmark") {
  group = "verification"
  description = "Runs the throughput benchmark against a PostgreSQL on localhost."
  testClassesDirs = sourceSets["test"].output.classesDirs
  classpath = sourceSets["test"].runtimeClasspath
  useJUnitPlatform {
    includeTags("benchmark")
  }
  // Tuned with BENCH_* environment variables, which Gradle does not track as
  // task inputs; without this a second run with different settings would be
  // skipped as up-to-date.
  outputs.upToDateWhen { false }
  testLogging {
    showStandardStreams = true
  }
}
