import java.security.MessageDigest

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


    compileOnly("io.micronaut:micronaut-http-client")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("io.micronaut:micronaut-http-client")
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
        replaceLogbackXml = true
    }
}



publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
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

signing {
    useGpgCmd()
    sign(publishing.publications["maven"])
}

java {
    withSourcesJar()
    withJavadocJar()
}

// Task to create checksums for Maven Central
tasks.register("generateChecksums") {
    dependsOn("jar", "sourcesJar", "javadocJar", "generatePomFileForMavenPublication")
    
    doLast {
        val libsDir = layout.buildDirectory.dir("libs").get().asFile
        val publicationsDir = layout.buildDirectory.dir("publications/maven").get().asFile
        val artifactId = project.name
        val version = project.version.toString()
        
        // Generate checksums for JAR files
        listOf(
            File(libsDir, "$artifactId-$version.jar"),
            File(libsDir, "$artifactId-$version-sources.jar"),
            File(libsDir, "$artifactId-$version-javadoc.jar")
        ).forEach { file ->
            if (file.exists()) {
                createChecksums(file)
            }
        }
        
        // Generate checksums for POM
        val pomFile = File(publicationsDir, "pom-default.xml")
        if (pomFile.exists()) {
            createChecksums(pomFile)
        }
    }
}

fun createChecksums(file: File) {
    val md5 = MessageDigest.getInstance("MD5")
    val sha1 = MessageDigest.getInstance("SHA-1")
    
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            md5.update(buffer, 0, bytesRead)
            sha1.update(buffer, 0, bytesRead)
        }
    }
    
    val md5Hash = md5.digest().joinToString("") { byte -> "%02x".format(byte) }
    val sha1Hash = sha1.digest().joinToString("") { byte -> "%02x".format(byte) }
    
    File(file.parent, "${file.name}.md5").writeText(md5Hash)
    File(file.parent, "${file.name}.sha1").writeText(sha1Hash)
    
    println("Generated checksums for ${file.name}")
}

// Task to create a ZIP bundle for manual upload to Central Portal
tasks.register<Zip>("createPublishingBundle") {
    dependsOn("generatePomFileForMavenPublication", "generateMetadataFileForMavenPublication", "signMavenPublication", "jar", "sourcesJar", "javadocJar", "generateChecksums")
    
    archiveFileName.set("${project.name}-${project.version}-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    // Create proper Maven directory structure
    val groupPath = project.group.toString().replace('.', '/')
    val artifactId = project.name
    val version = project.version.toString()
    val basePath = "$groupPath/$artifactId/$version"
    
    // Include POM with correct name and checksums
    from(layout.buildDirectory.dir("publications/maven")) {
        include("pom-default.xml")
        include("pom-default.xml.md5")
        include("pom-default.xml.sha1")
        include("pom-default.xml.asc")
        rename("pom-default.xml", "$artifactId-$version.pom")
        rename("pom-default.xml.md5", "$artifactId-$version.pom.md5")
        rename("pom-default.xml.sha1", "$artifactId-$version.pom.sha1")
        rename("pom-default.xml.asc", "$artifactId-$version.pom.asc")
        into(basePath)
    }
    
    // Include main JAR, checksums, and signature
    from(layout.buildDirectory.dir("libs")) {
        include("$artifactId-$version.jar")
        include("$artifactId-$version.jar.md5")
        include("$artifactId-$version.jar.sha1")
        include("$artifactId-$version.jar.asc")
        into(basePath)
    }
    
    // Include sources JAR, checksums, and signature
    from(layout.buildDirectory.dir("libs")) {
        include("$artifactId-$version-sources.jar")
        include("$artifactId-$version-sources.jar.md5")
        include("$artifactId-$version-sources.jar.sha1")
        include("$artifactId-$version-sources.jar.asc")
        into(basePath)
    }
    
    // Include javadoc JAR, checksums, and signature
    from(layout.buildDirectory.dir("libs")) {
        include("$artifactId-$version-javadoc.jar")
        include("$artifactId-$version-javadoc.jar.md5")
        include("$artifactId-$version-javadoc.jar.sha1")
        include("$artifactId-$version-javadoc.jar.asc")
        into(basePath)
    }
    
    doLast {
        println("Publishing bundle created: ${archiveFile.get().asFile}")
        println("Bundle structure for $groupPath/$artifactId/$version:")
        zipTree(archiveFile.get().asFile).forEach { file ->
            println("  ${file.path}")
        }
        println("\nUpload this ZIP to https://central.sonatype.com/publishing/deployments")
    }
}