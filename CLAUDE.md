# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Micronaut-Kool-Queue is a database-based queuing backend for Micronaut Framework. It provides asynchronous job processing using SQL databases with support for PostgreSQL, MySQL 8+, and SQLite. The library leverages the `FOR UPDATE SKIP LOCKED` clause for high-performance job polling without blocking.

## Project Structure

- **micronaut-kool-queue-core/**: Main library module containing the queue implementation
- **micronaut-kool-queue-sample/**: Sample application demonstrating library usage

## Build Commands

### Core Library (micronaut-kool-queue-core/)
```bash
./gradlew :micronaut-kool-queue-core:build
./gradlew :micronaut-kool-queue-core:test
./gradlew :micronaut-kool-queue-core:publishToMavenLocal
```

### Sample Application (micronaut-kool-queue-sample/)
```bash
./gradlew :micronaut-kool-queue-sample:build
./gradlew :micronaut-kool-queue-sample:test
./gradlew :micronaut-kool-queue-sample:run
```

### Publishing
```bash
./gradlew publish
./gradlew publishToSonatype
```

## Architecture

### Core Components

**Job Definition**: `KoolQueueJobs` entity represents queued jobs with:
- Queue name, job ID (UUID), class name, priority
- Status tracking (`TaskStatus`: PENDING, IN_PROGRESS, DONE, ERROR)
- Scheduling and completion timestamps

**Job Processing**: `KoolQueueScheduledJob` runs every 2 seconds to:
- Poll for pending jobs from database
- Instantiate job classes via reflection
- Execute jobs and update status based on results

**Job Service**: `KoolQueueJobsService` provides transactional operations for:
- Creating, updating, and querying jobs
- Status transitions and completion handling

**Job Interface**: `ApplicationJob<T>` interface for implementing custom jobs with:
- Type-safe payload processing
- Result handling with fold pattern

### Key Patterns

- **Annotation-based**: `@KoolQueueProducer` marks methods that create jobs
- **Database polling**: Scheduled job checks for work every 2 seconds
- **Transactional safety**: All database operations are wrapped in transactions
- **Type safety**: Jobs are parameterized with their payload type

## Technology Stack

- **Micronaut Framework 4.4.x**: Dependency injection and application framework
- **Kotlin**: Primary language with JPA entities
- **Micronaut Data**: Database access with JPA/Hibernate
- **Jackson**: JSON serialization for job metadata
- **PostgreSQL/MySQL/SQLite**: Supported databases
- **Gradle**: Build system with Kotlin DSL