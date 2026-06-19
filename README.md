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
	        implementation 'com.github.joaquindiez:micronaut-kool-queue:0.3.2-SNAPSHOT'
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
	        implementation("com.github.joaquindiez:micronaut-kool-queue:0.3.2-SNAPSHOT")
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
	    <version>0.3.2-SNAPSHOT</version>
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

Add the Kool Queue scheduler settings to your `application.yml`. All keys are
optional; the defaults shown are sensible for a single instance.

```yaml
micronaut:
  scheduler:
    kool-queue:
      enabled: true                      # master switch
      max-concurrent-tasks: 2            # global cap on jobs running at once

      # Graceful shutdown
      shutdown-timeout-seconds: 30       # how long to wait for in-flight jobs

      # Queue routing (see "Queues & multiple instances")
      queues: []                         # [] = poll every queue; or e.g. ["emails", "default"]

      # Schema isolation (see "Isolating the schema")
      # schema: kool_queue               # omit to use the connection's default schema (public)

      # Retries with exponential backoff (see "Retries")
      max-attempts: 5                    # total tries per job before dead-lettering (1 = no retry)
      retry-backoff-base-seconds: 5      # delay = base * 2^priorAttempts -> 5s, 10s, 20s, ...
      retry-backoff-max-seconds: 300     # cap on that delay

      # Multi-instance reaper (see "Queues & multiple instances")
      dead-worker-threshold-seconds: 60  # a worker is considered dead after this long without a heartbeat
```


# Usage

## 1. Create Your Job Class

Create a job by extending `ApplicationJob<T>` where `T` is the type of data your job will process, and annotate it with `@KoolQueueJob`:

```kotlin
@KoolQueueJob(queue = "emails")
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

`@KoolQueueJob` is meta-annotated with `@Singleton`, so the job is registered as
a bean automatically — no separate `@Singleton` needed. The queue a job routes
to is resolved with this precedence (highest first):

1. the `queue` argument of `processLater(data, queue = "...")`
2. an `override val queue: String = "..."` on the class (for dynamic/computed queues)
3. `@KoolQueueJob(queue = "...")`
4. the default queue (`"default"`)

> The legacy form — `@Singleton` plus `override val queue` — still works.

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

Jobs are processed automatically by the Kool Queue scheduler:

- A worker polls `ready_executions` continuously (every ~100ms) using
  `FOR UPDATE SKIP LOCKED`, claims a job atomically (moving it to
  `claimed_executions`), runs it, then removes the claim.
- The `max-concurrent-tasks` setting caps how many jobs run at once.
- On success the job's `finished_at` is stamped. On failure it is retried with
  backoff and, once the attempt budget is exhausted, recorded in
  `failed_executions` (see **Retries**).
- Scheduled jobs (`processLater(..., scheduledAt = ...)`) wait in
  `scheduled_executions` and are promoted to ready when their time arrives.

## Retries

When `process` returns `Result.failure` (or throws), the job is retried instead
of failing immediately. Each attempt increments the job's `attempts` counter and
re-enqueues it with an exponential backoff delay (`retry-backoff-base-seconds *
2^priorAttempts`, capped at `retry-backoff-max-seconds`). Once `max-attempts` is
reached the job is dead-lettered into `failed_executions`.

Tune it globally under `micronaut.scheduler.kool-queue` (see Scheduler
Configuration), or per job class:

```kotlin
@KoolQueueJob(queue = "emails", maxAttempts = 10)   // overrides the global max-attempts
class EmailNotificationJob : ApplicationJob<EmailData>() { /* ... */ }
```

Set `max-attempts: 1` to disable retries (fail on first error).

## Queues & multiple instances

A job's queue comes from `@KoolQueueJob(queue = "...")`, an `override val queue`,
or the per-call `processLater(data, queue = "...")` (see precedence above).

By default a worker polls **all** queues. To dedicate an instance to specific
queues, list them in priority order — earlier queues drain first:

```yaml
micronaut:
  scheduler:
    kool-queue:
      queues: ["emails", "default"]   # this instance ignores every other queue
```

You can run **multiple instances against the same database**: `FOR UPDATE SKIP
LOCKED` distributes work without double-processing. If an instance crashes
mid-job, its claimed jobs would otherwise be stranded — a reaper periodically
detects workers whose heartbeat is older than `dead-worker-threshold-seconds`,
re-enqueues their claimed jobs, and removes the dead worker. Keep that threshold
comfortably above ~30s.

## Isolating the schema

By default Kool Queue creates its tables in the connection's default schema
(usually `public`). Set `schema` to keep them in a dedicated Postgres schema
(created automatically if missing):

```yaml
micronaut:
  scheduler:
    kool-queue:
      schema: kool_queue
```

## Advanced Examples

### Complex Data Types

```kotlin
@KoolQueueJob(queue = "processing")
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
