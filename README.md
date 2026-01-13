# Micronaut-Kool-Queue: DB-based queuing backend for Micronaut

Kool Queue is a DB-based queuing backend for Micronaut Framework, designed with simplicity and performance in mind.

Kool Queue uses PostgreSQL and leverages the FOR UPDATE SKIP LOCKED clause to avoid blocking and waiting on locks when polling jobs.


# Installation

To get a Git project into your build:


### Gradle

Step 1. Add the JitPack repository to your build file

Add it in your root settings.gradle at the end of repositories:

```
	dependencyResolutionManagement {
		repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
		repositories {
			mavenCentral()
			maven { url 'https://jitpack.io' }
		}
	}
	
```
Step 2. Add the dependency

```
dependencies {
	        implementation 'com.github.joaquindiez:micronaut-kool-queue:0.3.0-SNAPSHOT'
	}

```

### Gradle.kts

Step 1. Add the JitPack repository to your build file
Add it in your settings.gradle.kts at the end of repositories:

```
	dependencyResolutionManagement {
		repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
		repositories {
			mavenCentral()
			maven { url = uri("https://jitpack.io") }
		}
	}
	
```
    
Step 2. Add the dependency

```
dependencies {
	        implementation("com.github.joaquindiez:micronaut-kool-queue:0.3.0-SNAPSHOT")
	}

```

### Maven

Add to pom.xml

```
<repositories>
		<repository>
		    <id>jitpack.io</id>
		    <url>https://jitpack.io</url>
		</repository>
	</repositories>
```

Step 2. Add the dependency

```
<dependency>
	    <groupId>com.github.joaquindiez</groupId>
	    <artifactId>micronaut-kool-queue</artifactId>
	    <version>0.3.0-SNAPSHOT</version>
	</dependency>
```



# High performance requirements

Kool Queue was designed for the highest throughput when used with PostgreSQL 9.5+, as it supports FOR UPDATE SKIP LOCKED.
You can use it with older versions, but in that case, you might run into lock waits if you run multiple workers for the same queue.


# Configuration

## Database Setup

Kool Queue requires a configured datasource with JPA/Hibernate. The library uses database tables to store and manage job queues.

### Required Dependencies

Add these dependencies to your project:

**Gradle (Groovy)**
```groovy
dependencies {
    implementation 'io.micronaut.data:micronaut-data-hibernate-jpa'
    implementation 'io.micronaut.sql:micronaut-jdbc-hikari'
    runtimeOnly 'org.postgresql:postgresql'
}
```

**Gradle (Kotlin DSL)**
```kotlin
dependencies {
    implementation("io.micronaut.data:micronaut-data-hibernate-jpa")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    runtimeOnly("org.postgresql:postgresql")
}
```

**Maven**
```xml
<dependencies>
    <dependency>
        <groupId>io.micronaut.data</groupId>
        <artifactId>micronaut-data-hibernate-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micronaut.sql</groupId>
        <artifactId>micronaut-jdbc-hikari</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

### Datasource Configuration

Add the datasource configuration to your `application.yml`:

```yaml
datasources:
  default:
    url: jdbc:postgresql://localhost:5432/your_database
    username: your_username
    password: your_password
    driver-class-name: org.postgresql.Driver
    db-type: postgres
    dialect: POSTGRES

jpa:
  default:
    entity-scan:
      packages:
        - com.yourcompany.domain             # Your application entities
    properties:
      hibernate:
        hbm2ddl:
          auto: update  # Creates tables automatically
```
    
## Scheduler Configuration

Add the Kool Queue scheduler settings to your `application.yml`:

```yaml
micronaut:
  scheduler:
    kool-queue:
      enabled: true
      max-concurrent-tasks: 3
      default-interval: 30s
      default-initial-delay: 10s
      shutdown-timeout-seconds: 30
```


# Usage

## 1. Create Your Job Class

Create a job by extending `ApplicationJob<T>` where `T` is the type of data your job will process:

```kotlin
@Singleton
class EmailNotificationJob : ApplicationJob<EmailData>() {

  private val logger = LoggerFactory.getLogger(javaClass)

  override fun process(data: EmailData): Result<Boolean> {
    val emailData = data as EmailData
    
    return try {
      // Your job logic here
      logger.info("Sending email to ${emailData.recipient}: ${emailData.subject}")
      
      // Simulate email sending
      Thread.sleep(1000)
      
      logger.info("Email sent successfully")
      Result.success(true)
    } catch (e: Exception) {
      logger.error("Failed to send email", e)
      Result.failure(e)
    }
  }
}

data class EmailData(
  val recipient: String,
  val subject: String,
  val body: String
)
```

## 2. Queue Jobs for Processing

### From a Controller

```kotlin
@Controller("/notifications")
class NotificationController(private val emailJob: EmailNotificationJob) {

  @Post("/send-email")
  fun sendEmail(@Body emailData: EmailData): HttpResponse<String> {
    // Queue the job for background processing
    val jobRef =  emailJob.processLater(emailData)
    println("Job ID: ${jobRef.jobId}")
    
    return HttpResponse.ok("Email queued for sending")
  }
}
```

### From a Service

```kotlin
@Singleton
class UserService(private val emailJob: EmailNotificationJob) {

  fun registerUser(user: User) {
    // Save user to database
    userRepository.save(user)
    
    // Queue welcome email
    val welcomeEmail = EmailData(
      recipient = user.email,
      subject = "Welcome to our platform!",
      body = "Thank you for joining us, ${user.name}!"
    )
    
    emailJob.processLater(welcomeEmail)
  }
}
```

## 3. Job Execution

Jobs are automatically processed by the Kool Queue scheduler:
- Polls the database every 2 seconds for pending jobs
- Respects the `max-concurrent-tasks` configuration
- Updates job status automatically (PENDING → IN_PROGRESS → DONE/ERROR)
- Handles failures gracefully with proper error logging

## Advanced Examples

### Complex Data Types

```kotlin
@Singleton
class DataProcessingJob : ApplicationJob<ProcessingRequest>() {

  override fun process(data: ProcessingRequest): Result<Boolean> {
    val request = data as ProcessingRequest
    
    return try {
      when (request.type) {
        "ANALYSIS" -> performAnalysis(request.payload)
        "EXPORT" -> exportData(request.payload)
        "CLEANUP" -> cleanupResources(request.payload)
        else -> throw IllegalArgumentException("Unknown processing type: ${request.type}")
      }
      
      Result.success(true)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}

data class ProcessingRequest(
  val type: String,
  val payload: Map<String, Any>,
  val userId: Long,
  val timestamp: Instant = Instant.now()
)
```


# Inspiration

Kool Queue has been inspired by [Solid Queue](https://github.com/rails/solid_queue) and Rails.
We recommend checking out these projects as they're great examples from which we've learnt a lot.

# License

The library is available as open source under the terms of the [APACHE 2.0](./LICENSE)
