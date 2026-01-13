# Micronaut Kool Queue Sample

Sample application demonstrating the usage of the Micronaut Kool Queue library for database-based job queuing.

## Prerequisites

- Java 17+
- PostgreSQL database running on `localhost:5432`
- Database credentials: `postgres` / `changeme`

## Running the Application

```bash
./gradlew :micronaut-kool-queue-sample:run
```

The application will start on port **8080** by default.

## API Endpoints

### Task Endpoints

#### Enqueue Immediate Job

Enqueues a job for immediate processing.

```bash
curl -X GET http://localhost:8080/task
```

**Response:** `200 OK` (empty body)

**Behavior:** Creates a job that will be picked up by the scheduler and processed immediately. The job logs "Procesando Test Jobs -> Hello" and takes 10 seconds to complete.

---

#### Enqueue Scheduled Job

Enqueues a job scheduled to run 1 minute from now.

```bash
curl -X GET http://localhost:8080/task/scheduled
```

**Response:** `200 OK` (empty body)

**Behavior:** Creates a job scheduled to run 1 minute in the future. The job logs "Procesando Test Jobs -> Hello Scheduled" and takes 10 seconds to complete.

---

### Management Endpoints

#### Health Check

```bash
curl -X GET http://localhost:8080/health
```

**Response:**
```json
{
  "status": "UP"
}
```

---

#### Kool Queue Scheduler Status

```bash
curl -X GET http://localhost:8080/kool-queue-scheduler
```

**Response:** Returns the current status of the Kool Queue scheduler including active tasks and configuration.

---

## Configuration

The sample application is configured with:

| Property | Value |
|----------|-------|
| Max Concurrent Tasks | 4 |
| Shutdown Timeout | 30 seconds |
| Database | PostgreSQL |
| Management Endpoints | Enabled |

See `src/main/resources/application.yml` for the full configuration.

---

## Micronaut Documentation

- [User Guide](https://docs.micronaut.io/4.6.2/guide/index.html)
- [API Reference](https://docs.micronaut.io/4.6.2/api/index.html)
- [Configuration Reference](https://docs.micronaut.io/4.6.2/guide/configurationreference.html)
- [Micronaut Guides](https://guides.micronaut.io/index.html)

## Feature Documentation

- [Shadow Gradle Plugin](https://plugins.gradle.org/plugin/com.github.johnrengelman.shadow)
- [Micronaut Gradle Plugin](https://micronaut-projects.github.io/micronaut-gradle-plugin/latest/)
- [GraalVM Gradle Plugin](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html)
- [Testcontainers](https://www.testcontainers.org/)
- [Micronaut Hikari JDBC Connection Pool](https://micronaut-projects.github.io/micronaut-sql/latest/guide/index.html#jdbc)
- [Micronaut KSP](https://docs.micronaut.io/latest/guide/#kotlin)
- [Kotlin Symbol Processing](https://kotlinlang.org/docs/ksp-overview.html)
- [Micronaut AOT](https://micronaut-projects.github.io/micronaut-aot/latest/guide/)
- [Micronaut Serialization Jackson](https://micronaut-projects.github.io/micronaut-serialization/latest/guide/)


