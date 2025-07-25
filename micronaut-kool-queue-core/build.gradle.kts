plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("org.jetbrains.kotlin.plugin.allopen") version "1.9.23"
    id("org.jetbrains.kotlin.plugin.jpa") version "1.9.23"
    id("com.google.devtools.ksp") version "1.9.23-1.0.19"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("io.micronaut.library") version "4.4.0"
    id("io.micronaut.aot") version "4.4.0"
    id("maven-publish")
}


version = project.findProperty("version") as String? ?: "0.2.0"
group = project.findProperty("group") as String? ?: "com.joaquindiez"

val kotlinVersion=project.properties.get("kotlinVersion")

repositories {
    mavenCentral()
}

dependencies {
    ksp("io.micronaut:micronaut-http-validation")
    ksp("io.micronaut.serde:micronaut-serde-processor")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("io.micronaut.serde:micronaut-serde-jackson")

    implementation("io.micronaut.data:micronaut-data-jpa")
    implementation("io.micronaut.data:micronaut-data-jdbc")
    implementation("javax.persistence:javax.persistence-api") // API de JPA
    implementation("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlinVersion}")

    implementation("com.github.f4b6a3:uuid-creator:5.3.7")
    
    // SLF4J API only - consumers choose their logging implementation
    implementation("org.slf4j:slf4j-api:2.0.7")

    compileOnly("io.micronaut:micronaut-http-client")
    runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin")
    
    // Test dependencies
    testImplementation("io.micronaut:micronaut-http-client")
    testRuntimeOnly("ch.qos.logback:logback-classic")  // Only for tests
}


java {
    sourceCompatibility = JavaVersion.toVersion("17")
    targetCompatibility = JavaVersion.toVersion("17")
}

kotlin {
    jvmToolchain(17)
}


graalvmNative.toolchainDetection = false
micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.joaquindiez.*")
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
        replaceLogbackXml = false  // Disabled to avoid logback dependency
    }
}



publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
            
            groupId = project.group.toString()
            artifactId = "micronaut-kool-queue-core"
            version = project.version.toString()

            pom {
                name.set("Micronaut Kool Queue Core")
                description.set("Database-based queuing backend for Micronaut Framework with high-performance job processing")
                url.set("https://github.com/joaquindiez/micronaut-kool-queue")
                
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://opensource.org/licenses/Apache-2.0")
                    }
                }
                
                developers {
                    developer {
                        id.set("joaquindiez")
                        name.set("Joaquín Díez Gómez")
                        email.set("me@joaquindiez.com")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/joaquindiez/micronaut-kool-queue.git")
                    developerConnection.set("scm:git:ssh://github.com:joaquindiez/micronaut-kool-queue.git")
                    url.set("https://github.com/joaquindiez/micronaut-kool-queue")
                }
            }
        }
    }
}

