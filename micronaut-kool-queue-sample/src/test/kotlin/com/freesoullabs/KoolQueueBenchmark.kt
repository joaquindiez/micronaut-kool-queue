/**
 * Copyright 2024 Joaquín Díez Gómez
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.freesoullabs

import com.joaquindiez.koolQueue.KoolQueueMessageProducer
import com.joaquindiez.koolQueue.jobs.ApplicationJob
import com.joaquindiez.koolQueue.jobs.KoolQueueJob
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Throughput benchmark. Not part of `./gradlew test` — see docs/benchmark.md.
 *
 * Run with:  ./gradlew :micronaut-kool-queue-sample:benchmark
 */
@KoolQueueJob(queue = "bench")
class BenchJob : ApplicationJob<String>() {

  override fun process(data: String): Result<Boolean> {
    // A payload of exactly the configured duration, so the queue's own overhead
    // can be separated from the work: with 0 ms what is measured is the
    // machinery alone, and with a realistic duration the gap to
    // `threads / duration` is the per-job overhead.
    if (sleepMs > 0) Thread.sleep(sleepMs)
    processed.incrementAndGet()
    return Result.success(true)
  }

  companion object {
    val processed = AtomicInteger(0)
    val sleepMs: Long = System.getenv("BENCH_SLEEP_MS")?.toLong() ?: 0L
  }
}

@Tag("benchmark")
@MicronautTest(transactional = false, environments = ["benchmark"])
class KoolQueueBenchmark {

  @Inject
  lateinit var producer: KoolQueueMessageProducer

  @Test
  fun benchmark() {
    val n = System.getenv("BENCH_JOBS")?.toInt() ?: 300
    val reps = System.getenv("BENCH_REPS")?.toInt() ?: 3
    val producers = System.getenv("BENCH_PRODUCERS")?.toInt() ?: 1
    val label = System.getenv("BENCH_LABEL") ?: "kool-queue"

    // End-to-end is the only metric that stays meaningful once the worker keeps
    // up with the producer: jobs drain *while* they are still being enqueued,
    // so timing the drain separately measures only the tail and reports absurd
    // rates. Wall clock from first enqueue to last completion is well defined
    // either way, and comparable across configurations.
    val rates = mutableListOf<Double>()

    // Warm the poller up: it has an initial delay, and a rep starting inside
    // that window would bill the wait to the run.
    producer.send("warmup", BenchJob::class.java, "bench", null)
    val warmStart = System.currentTimeMillis()
    while (BenchJob.processed.get() == 0) {
      check(System.currentTimeMillis() - warmStart < 120_000) { "worker never started" }
      Thread.sleep(5)
    }

    repeat(reps) { rep ->
      BenchJob.processed.set(0)

      val t0 = System.currentTimeMillis()
      val threads = (0 until producers).map { p ->
        Thread {
          var i = p
          while (i < n) {
            producer.send("bench-$rep-$i", BenchJob::class.java, "bench", null)
            i += producers
          }
        }.apply { start() }
      }
      threads.forEach { it.join() }

      while (BenchJob.processed.get() < n) {
        check(System.currentTimeMillis() - t0 < 600_000) {
          "timed out at ${BenchJob.processed.get()}/$n"
        }
        Thread.sleep(2)
      }
      val totalMs = System.currentTimeMillis() - t0

      // Parallel execution makes double-claiming a real risk: settle briefly and
      // assert the counter landed on exactly n, not above it.
      Thread.sleep(1_000)
      val finalCount = BenchJob.processed.get()
      check(finalCount == n) { "expected exactly $n executions, got $finalCount (double-processing?)" }

      val rate = n * 1000.0 / totalMs
      rates += rate
      println("BENCHRUN label=$label rep=${rep + 1} jobs=$n total_ms=$totalMs e2e_per_sec=${"%.1f".format(rate)}")
    }

    val mean = rates.average()
    val sd = if (rates.size < 2) 0.0 else
      kotlin.math.sqrt(rates.sumOf { (it - mean) * (it - mean) } / (rates.size - 1))
    println(
      "BENCHSTAT label=$label jobs=$n reps=$reps e2e " +
        "mean=${"%.1f".format(mean)} sd=${"%.1f".format(sd)} " +
        "min=${"%.1f".format(rates.min())} max=${"%.1f".format(rates.max())}"
    )
  }
}
