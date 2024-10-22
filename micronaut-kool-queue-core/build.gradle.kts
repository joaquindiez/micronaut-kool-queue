plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("org.jetbrains.kotlin.plugin.allopen") version "1.9.23"
    id("org.jetbrains.kotlin.plugin.jpa") version "1.9.23"
    id("com.google.devtools.ksp") version "1.9.23-1.0.19"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("io.micronaut.library") version "4.4.0"
    id("io.micronaut.aot") version "4.4.0"
    id("maven-publish")
    id("signing")
}


version = "0.1.3"
group = "com.joaquindiez"

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


    compileOnly("io.micronaut:micronaut-http-client")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("io.micronaut:micronaut-http-client")
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
        replaceLogbackXml = true
    }
}



publishing {

    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "com.joaquindiez"
            artifactId = "micronaut-kool-queue-core"
            version = "0.1.2"


            pom {
                name.set("micronaut-kool-queue-core")
                description.set("Library to implement asyncronous application in Micronaut using queues in relational databases")
                url.set("https://github.com/joaquindiez/micronaut-kool-queue")
                licenses {
                    license {
                        name.set("Apache 2.0 License")
                        url.set("https://opensource.org/license/apache-2-0")
                    }
                }
                developers {
                    developer {
                        id.set("joaquindiez")
                        name.set("Joaquin Diez")
                        email.set("me@joaqundiez.com")
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
    repositories {
       /* maven {
            name = "localMaven"
            url = uri("file:///Users/j10/.m2/repository/") // Define un directorio local para el repositorio
        }

        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/joaquindiez/micronaut-kool-queue")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
            }
        }

        */

        maven {
            name = "sonatype"
            url = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")

            credentials {
                username = project.findProperty("sonatypeUsername") as String? ?: ""
                password = project.findProperty("sonatypePassword") as String? ?: ""
            }
        }
    }
    publications {
        register<MavenPublication>("gpr") {
            from(components["java"])
        }
    }

}

signing {
    sign(publishing.publications["mavenJava"])
}
