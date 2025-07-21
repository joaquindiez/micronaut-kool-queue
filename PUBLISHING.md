# Publishing to Maven Central

This document describes how to publish the Micronaut Kool Queue library to Maven Central using the Sonatype Central Portal.

## Prerequisites

### 1. Sonatype Central Portal Account
1. Create an account at https://central.sonatype.com/
2. Verify your namespace (e.g., `com.joaquindiez`)
3. Deploy your namespace (click "Deploy" in the namespaces section)

### 2. GPG Key for Signing
Maven Central requires all artifacts to be signed with GPG.

```bash
# Generate a GPG key if you don't have one
gpg --gen-key

# List your keys to get the key ID
gpg --list-secret-keys --keyid-format LONG

# Export public key to a keyserver
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID

# Export secret key ring (for older GPG versions)
gpg --export-secret-keys > ~/.gnupg/secring.gpg
```

## Configuration

### 1. Gradle Configuration

The project is already configured with the necessary plugins and settings:

**Root `build.gradle`:**
```groovy
plugins {
    id "com.github.hierynomus.license" version "0.16.1"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
            snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
            username = project.findProperty("centralUsername") ?: System.getenv("CENTRAL_USERNAME") ?: ""
            password = project.findProperty("centralPassword") ?: System.getenv("CENTRAL_PASSWORD") ?: ""
        }
    }
    useStaging = false
    connectTimeout = Duration.ofMinutes(3)
    clientTimeout = Duration.ofMinutes(3)
}
```

**Core module `build.gradle.kts`:**
```kotlin
plugins {
    id("maven-publish")
    id("signing")
    // ... other plugins
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
    sign(publishing.publications["maven"])
}

java {
    withSourcesJar()
    withJavadocJar()
}
```

### 2. Credentials Configuration

#### Option A: Global `~/.gradle/gradle.properties` (Recommended)
```properties
# Sonatype Central Portal credentials
centralUsername=YOUR_CENTRAL_USERNAME
centralPassword=YOUR_CENTRAL_PASSWORD

# GPG signing configuration
signing.keyId=YOUR_GPG_KEY_ID
signing.password=YOUR_GPG_PASSPHRASE
signing.secretKeyRingFile=/Users/youruser/.gnupg/secring.gpg
```

#### Option B: Environment Variables
```bash
export CENTRAL_USERNAME="your_username"
export CENTRAL_PASSWORD="your_password"
export ORG_GRADLE_PROJECT_signing.keyId="your_key_id"
export ORG_GRADLE_PROJECT_signing.password="your_passphrase"
```

### 3. Get Sonatype Central Portal Credentials

1. Go to https://central.sonatype.com/account
2. Click "Generate User Token"
3. Copy the username and password
4. Use these values for `centralUsername` and `centralPassword`

## Publishing Process

### 1. Snapshot Publishing (Development)



Summary

Both workflows now work independently and correctly:

1. Local Development: ./gradlew :micronaut-kool-queue-core:publishToMavenLocal
   - Works without GPG signing (signMavenPublication task is SKIPPED)
   - Perfect for local testing and development
2. Production Bundle: ./gradlew :micronaut-kool-queue-core:createPublishingBundle -Psigning.keyId=265E22ED -Psigning.password="only4theWeak!"
   - Creates properly signed bundle for Maven Central
   - All files include .asc signatures, MD5/SHA1 checksums
   - Ready for upload to https://central.sonatype.com/publishing/deployments
For development versions, use `-SNAPSHOT` suffix:

```properties
version=0.2.0-SNAPSHOT
```

Publish snapshot:
```bash
./gradlew publishToSonatype
```

Or publish only the core module:
```bash
./gradlew :micronaut-kool-queue-core:publishMavenPublicationToSonatypeRepository
```

### 2. Release Publishing (Production)

For release versions, remove the `-SNAPSHOT` suffix:

```properties
version=0.2.0
```

Publish release:
```bash
./gradlew publishToSonatype
```

**Note:** Release versions go through staging and require manual promotion in the Sonatype Central Portal.

### 3. Manual Upload (Alternative Method)

If automated publishing fails or you prefer manual control, you can create a ZIP bundle for manual upload:

```bash
# Create a ZIP bundle with all required artifacts
./gradlew :micronaut-kool-queue-core:createPublishingBundle
```

This creates a ZIP file in `micronaut-kool-queue-core/build/distributions/` containing:
- Main JAR + signature
- Sources JAR + signature  
- Javadoc JAR + signature
- POM file + signature
- Gradle metadata + signature

**Manual Upload Steps:**
1. Run the bundle creation command above
2. Go to https://central.sonatype.com/publishing/deployments
3. Click "Upload Bundle"
4. Select the generated ZIP file
5. Click "Upload Bundle" 
6. Review and publish the deployment

### 4. Local Testing

Test your library locally before publishing to Maven Central:

#### Publish to Local Maven Repository
```bash
# Build and publish to local repository
./gradlew :micronaut-kool-queue-core:build
./gradlew :micronaut-kool-queue-core:publishToMavenLocal

# Verify local publication
ls ~/.m2/repository/com/joaquindiez/micronaut-kool-queue-core/0.2.0/
```

#### Test in a Local Project

1. **Create a test Micronaut project:**
```bash
mn create-app test-kool-queue --features=data-jdbc,postgresql --lang=kotlin
cd test-kool-queue
```

2. **Configure the dependency in `build.gradle.kts`:**
```kotlin
repositories {
    mavenLocal()  // IMPORTANT: Add BEFORE mavenCentral()
    mavenCentral()
}

dependencies {
    implementation("com.joaquindiez:micronaut-kool-queue-core:0.2.0")
    // ... other dependencies
}
```

3. **Use the library in your code:**
```kotlin
import com.joaquindiez.koolQueue.services.KoolQueueJobsService
import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import jakarta.inject.Inject
import java.util.*

@Controller
class TestController {
    
    @Inject
    lateinit var koolQueueJobsService: KoolQueueJobsService
    
    @Get("/test-queue")
    fun testQueue(): String {
        val job = KoolQueueJobs(
            jobId = UUID.randomUUID(),
            className = "com.example.TestJob",
            metadata = """{"message": "Hello from Kool Queue!"}"""
        )
        
        koolQueueJobsService.addTask(job)
        return "Job added successfully!"
    }
}
```

4. **Run the test project:**
```bash
./gradlew run
```

5. **Verify it works:**
- Visit `http://localhost:8080/test-queue`
- Check your database for the queued job
- Monitor the scheduled job processor logs

## Using the Published Library

Once published, users can include your library in their projects:

### Gradle (Kotlin DSL)
```kotlin
dependencies {
    implementation("com.joaquindiez:micronaut-kool-queue-core:0.2.0-SNAPSHOT")
}
```

### Gradle (Groovy DSL)
```groovy
dependencies {
    implementation 'com.joaquindiez:micronaut-kool-queue-core:0.2.0-SNAPSHOT'
}
```

### Maven
```xml
<dependency>
    <groupId>com.joaquindiez</groupId>
    <artifactId>micronaut-kool-queue-core</artifactId>
    <version>0.2.0-SNAPSHOT</version>
</dependency>
```

## Troubleshooting

### Common Issues

#### 401 Unauthorized
- Verify your Central Portal credentials are correct
- Ensure you're using Portal tokens, not OSSRH tokens
- Check that your tokens haven't expired

#### 403 Forbidden
- Ensure your namespace is deployed (not just verified)
- For snapshots, make sure version ends with `-SNAPSHOT`
- For releases, make sure version doesn't end with `-SNAPSHOT`

#### Signing Issues
- Verify GPG key is properly configured
- Check that secret key ring file exists
- Ensure GPG key is published to a keyserver

#### Publication Not Found
- Snapshots are available immediately
- Releases may take 10-30 minutes to appear in search
- Check https://central.sonatype.com/ for publication status

### Debug Commands

```bash
# Check available publishing tasks
./gradlew tasks --group publishing

# Dry run publishing
./gradlew publishToSonatype --dry-run

# Publish with debug info
./gradlew publishToSonatype --info --stacktrace
```

### Verification

After successful publishing, verify your artifact is available:

1. **Central Portal**: https://central.sonatype.com/artifact/com.joaquindiez/micronaut-kool-queue-core
2. **Maven Central Search**: https://search.maven.org/search?q=g:com.joaquindiez
3. **Direct URL**: https://repo1.maven.org/maven2/com/joaquindiez/micronaut-kool-queue-core/

## Resources

- [Sonatype Central Portal Documentation](https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/)
- [Gradle Nexus Publish Plugin](https://github.com/gradle-nexus/publish-plugin)
- [Maven Central Repository](https://central.sonatype.com/)
- [GPG Documentation](https://gnupg.org/documentation/)