# Micronaut-Kool-Queue: DB-based queuing backend for Micronaut

Kool Queue  is a DB-based queuing backend for Micronaut Framework, designed with simplicity and performance in mind.

Kool Queue can be used with SQL databases such as PostgreSQL, and it leverages the FOR UPDATE SKIP LOCKED clause, if available, to avoid blocking and waiting on locks when polling jobs.


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
	        implementation 'com.github.joaquindiez:micronaut-kool-queue:Tag'
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
	        implementation("com.github.joaquindiez:micronaut-kool-queue:Tag")
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
	    <version>Tag</version>
	</dependency>
```



# High performance requirements

Koll Queue was designed for the highest throughput when used with MySQL 8+ or PostgreSQL 9.5+, as they support FOR UPDATE SKIP LOCKED.
You can use it with older versions, but in that case, you might run into lock waits if you run multiple workers for the same queue.
You can also use it with SQLite on smaller applications.

# Configuration


Add this to application.yaml

```

micronaut:
    scheduler:
    kool-queue:
      max-concurrent-tasks: 2      
```


# Usage

## 1. Create Your Job Class

Create a job by extending `ApplicationJob<T>` where `T` is the type of data your job will process:

```kotlin
@Singleton
class EmailNotificationJob : ApplicationJob<EmailData>() {

  private val logger = LoggerFactory.getLogger(javaClass)

  override fun getType(): Class<EmailData> {
    return EmailData::class.java
  }

  override fun process(data: Any): Result<Boolean> {
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
    emailJob.processLater(emailData)
    
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

  override fun getType(): Class<ProcessingRequest> = ProcessingRequest::class.java

  override fun process(data: Any): Result<Boolean> {
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
