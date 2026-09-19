# From 10 to 500 Jobs a Second: What Benchmarking Taught Me About My Own Queue

*I built a Postgres-backed job queue for Micronaut. Then I measured it properly
and discovered it wasn't doing what I had designed it to do.*

---

## Why put the queue in the database

Every background-job setup I've run in production has had the same shape: an
application, a database, and then a *second* piece of infrastructure — Redis,
RabbitMQ, SQS — whose only job is to hold work in flight. That second system has
its own failure modes, its own backups, its own monitoring, and its own way of
disagreeing with the database about what actually happened.

The alternative is older than all of them: put the jobs in the database you
already have. Rails made this fashionable again with **Solid Queue**, and the
argument holds up well:

- **Transactional enqueue.** The job row and your domain row commit together.
  No more "the order was saved but the confirmation email vanished."
- **One thing to operate.** One backup, one failover story, one dashboard.
- **You can `SELECT` your queue.** Debugging a stuck job is a query, not an
  expedition into a Redis instance.

The classic objection — "polling a database won't keep up" — stopped being true
in PostgreSQL 9.5, when `FOR UPDATE SKIP LOCKED` arrived. It lets a worker grab
the next available rows and *skip* the ones another worker has already locked,
instead of queueing up behind them. That single clause is what turns a table
into a work queue.

So I built **Kool Queue**: a database-backed queuing backend for the Micronaut
Framework, written in Kotlin, running on PostgreSQL. A job looks like this:

```kotlin
@KoolQueueJob(queue = "emails", maxAttempts = 3)
class EmailNotificationJob : ApplicationJob<EmailData>() {
  override fun process(data: EmailData): Result<Boolean> {
    mailer.send(data.recipient, data.subject, data.body)
    return Result.success(true)
  }
}

// anywhere in your app
emailJob.processLater(EmailData(user.email, "Welcome!", body))
```

Under the hood there are six tables — a permanent job record, plus
ready / scheduled / claimed / failed executions and worker heartbeats — and
three periodic tasks: a **dispatcher** that promotes scheduled jobs when they
come due, a **worker** that atomically claims ready jobs and runs them, and a
**reaper** that rescues jobs orphaned by a crashed worker.

It worked. Jobs went in, jobs came out, retries backed off exponentially, dead
workers got reaped. I was happy with it.

Then I wrote a benchmark.

## The first benchmark measured nothing at all

My initial harness did the obvious thing: time the enqueue phase, then time the
drain phase, and divide. It reported **30,000 jobs per second**.

That number was garbage, and the reason is worth internalising. Once the worker
keeps up with the producer, jobs drain *while they are still being enqueued*.
By the time the "drain phase" starts, most of the work is already done, so you
are timing the tail of the run and dividing by the whole batch.

The fix is to measure something that stays valid whether or not the phases
overlap: **end-to-end throughput** — wall clock from the first enqueue to the
last job finishing, divided by the number of jobs. It is also, not
coincidentally, what a user actually experiences.

Two payloads, answering two different questions:

- **An empty job**, which measures the queue machinery alone: enqueue, claim,
  dispatch, execute, bookkeeping.
- **A job that sleeps 100 ms**, the realistic case. The gap between the measured
  rate and the theoretical `threads / duration` is exactly the overhead the
  queue adds per job.

And one assertion that matters more than any timing: the benchmark verifies that
**exactly N jobs ran, never N+1**. Parallel claiming makes double-execution a
real risk, and a throughput number from a run that processed some jobs twice is
worse than no number at all.

## The number that gave the bug away

Here is the baseline, with the default configuration of five execution threads:

| | empty job | 100 ms job |
|---|---:|---:|
| Baseline | 10.0/s | 9.9/s |

Stare at that for a second. A job that does *nothing* and a job that sleeps for
a tenth of a second performed **identically**. That is the unmistakable
signature of a system bound by its own clock rather than by the work it is
doing.

The cause was three lines deep in the poller: it claimed `limit = 1` — a
hard-coded constant — and it ticked every 0.1 seconds. One job per tick, ten
ticks per second, ten jobs per second. Forever. Raising the configured
concurrency did nothing at all, because the concurrency was *built* but never
*fed*. I had a five-lane road with a one-car-at-a-time toll booth at the
entrance.

You don't find that bug by reading the code. I know, because I had read that
code many times. You find it by measuring two workloads that should differ and
noticing that they don't.

## Fix #1 — claim what you can actually run

The batch size stopped being a constant and became **the free capacity of the
execution pool**. This is what Solid Queue does with
`ReadyExecution.claim(queues, pool.available_capacity, process_id)`, and the
logic is symmetrical: claim fewer than you can run and the pool starves; claim
more and you have jobs reserved by a worker that can't get to them — jobs that
the reaper would later have to rescue if that worker died.

Claimed jobs also went to a dedicated thread pool instead of running inline
inside the poller tick. Without that, a bigger batch would just have been a
longer sequential loop.

One subtlety that took a while to get right: the pool reserves a slot **when a
job is submitted**, not when it starts running. Otherwise two polls in quick
succession can both count the same free slot and you overcommit.

| | empty job | 100 ms job |
|---|---:|---:|
| Baseline | 10.0/s | 9.9/s |
| **+ capacity-based claim & execution pool** | **49.9/s** | **24.9/s** |

Five times better, and now the two workloads finally disagree with each other.
But 24.9/s for 100 ms jobs is suspiciously close to *half* of the theoretical
ceiling (5 threads ÷ 0.1 s = 50/s). Where did the other half go?

## Fix #2 — wake the poller when a job finishes

Into the clock, again. With a 0.1 s tick, a worker can never exceed
`10 × threads` jobs per second no matter how fast the jobs themselves complete.
Half of every cycle was spent waiting for the next tick while slots sat free.

So the pool now notifies the poller the moment a job finishes, and the poller
claims again immediately. It is Solid Queue's `on_idle: -> { wake_up }`, adapted
to a coroutine-based scheduler: instead of a self-pipe interrupting a sleep, a
wake-up sets a latch on the task and launches an execution.

That latch is the whole trick. A wake-up can arrive while the task is already
running, or find the semaphores saturated — in both cases the flag survives, and
whoever finishes re-triggers the task. It is the same property a self-pipe gives
you: the byte stays in the pipe until someone reads it. The flag is cleared when
the poll *starts*, not when it ends, so a wake-up that arrives mid-poll counts
as pending instead of being silently swallowed.

| | empty job | 100 ms job |
|---|---:|---:|
| Baseline | 10.0/s | 9.9/s |
| + capacity-based claim & execution pool | 49.9/s | 24.9/s |
| **+ wake the poller on completion** | **463.9/s** | **47.0/s** |

**47.0/s is 94% of the theoretical ceiling** of the configured concurrency. The
polling interval is no longer a factor: throughput is now governed by thread
count and job duration, which is exactly what you want from a dial. For empty
jobs, the limit has moved to the database round trip — a far more honest place
for it to be.

## Fix #3 — the optimisation that didn't show up

Two N+1 patterns were left in the claim path. For a batch of N jobs it did N
inserts into `claimed_executions`, N deletes from `ready_executions`, and N
selects to re-read rows the claim query had *just* read. Collapsing them into a
multi-row insert, a `DELETE ... IN`, and a `SELECT ... IN`:

| statements per 200 jobs | before | after |
|---|---:|---:|
| INSERT claimed_executions | 201 | 71 |
| DELETE ready_executions | 201 | 71 |
| SELECT job by id | 201 | 71 |
| **total** | **603** | **213** |

2.8× fewer round trips. And the effect on end-to-end throughput was…
**nothing measurable**: 985.7 ± 259.3 against 919.4 ± 243.4 jobs/s. Well within
the noise.

That is not a failed optimisation; it is a benchmark honestly reporting its own
blind spot. PostgreSQL was running on the same machine, where a round trip is
nearly free, and with a single worker nobody was contending for those locks. But
those inserts and deletes happen *inside* the claim transaction — the one
holding the `FOR UPDATE SKIP LOCKED` locks until it commits. Every extra round
trip is time other workers spend waiting. With several workers, or with the
database on the other side of a network, it matters a great deal.

So I reported the metric that was actually deterministic — the statement count —
instead of a throughput delta that JIT noise eats. If you only trust your
headline number, you will reject good changes and ship bad ones.

## Versus Solid Queue

Since Kool Queue is openly inspired by Solid Queue, comparing them seemed only
fair. Same machine, same PostgreSQL 16.13 instance, same method, five worker
threads on both sides, three repetitions, Solid Queue 1.7.0. Numbers are
mean ± standard deviation in jobs/second:

| | Kool Queue | Solid Queue | |
|---|---:|---:|---:|
| 100 ms job | 47.5 ± 0.8 | 39.4 ± 0.5 | 1.2× |
| Empty job | 507.6 ± 133.2 | 81.2 ± 1.9 | 6.3× |
| Enqueue only | ~1,150 | 275 | 4.2× |

Expressed as per-job overhead outside the job body: roughly **5 ms for Kool
Queue against 27 ms for Solid Queue**.

Now the part that a benchmark chart on a landing page would leave out:

- **The architectures are equivalent in the hot loop.** Claim by free capacity,
  execute in a pool, wake on completion — I took all three from Solid Queue's
  design. What the gap measures is mostly *platform*: JVM against Ruby,
  hand-written SQL against ActiveRecord loading, deserialising, updating and
  destroying a record per job.
- **This is one worker process against one worker process, which flatters the
  JVM.** Ruby has a GVL, so Solid Queue scales in production by running several
  worker processes under its supervisor, not by raising thread counts. A
  realistic 4 processes × 5 threads deployment would look very different. Kool
  Queue has no multi-process supervisor; it scales inside a single JVM.
- **PostgreSQL was on localhost**, so network latency — which would penalise the
  chattier design — is entirely absent.

The honest summary is not "Kool Queue is 6× faster than Solid Queue." It is:
*on a single process, on the same machine, with the database next door, the JVM
gives you a lower per-job overhead* — and if that is your deployment shape, that
difference is real.

## Two traps worth knowing about

**The producer can become the bottleneck.** Once the consumer is fast, a
single-threaded producer caps the measurement and you end up benchmarking your
own test harness. The symptom is that the end-to-end rate stops responding to
consumer-side changes at all. Know your producer's standalone rate (~1,150/s
here for Kool Queue, ~275/s for Solid Queue) and if your end-to-end figure is
creeping towards it, add producer threads.

**Some improvements are invisible in your headline metric.** See fix #3. Pick a
deterministic secondary metric — statement counts, lock hold time, allocations —
for the changes whose benefit only appears under conditions your benchmark
doesn't reproduce.

## What I actually took away from this

1. **A benchmark's first job is to find your bugs, not to produce a number.**
   The 46× improvement wasn't clever optimisation. It was a hard-coded `limit =
   1` that had survived every code review I gave it, and only a measurement
   found it.
2. **If two very different workloads give you the same number, you are measuring
   something other than the work.** An empty job and a 100 ms job matching to
   within 1% was the single most informative data point in the entire exercise.
3. **Define the headline metric so it survives your own improvements.** My first
   metric broke precisely *because* the system got fast enough for the phases to
   overlap.
4. **Publish the caveats next to the numbers.** Single process, localhost
   database, synthetic payload. A benchmark nobody can reproduce — and whose
   conditions nobody can check — is marketing, not engineering.

Everything here is reproducible: the harness, the Solid Queue counterpart, the
exact environment variables and the schema-cleanup steps are in the repository,
and the whole comparison is one Gradle task plus one Ruby script.

**Kool Queue:** [github.com/joaquindiez/micronaut-kool-queue](https://github.com/joaquindiez/micronaut-kool-queue)
· method and full results in [`docs/benchmark.md`](https://github.com/joaquindiez/micronaut-kool-queue/blob/main/docs/benchmark.md)
· Apache 2.0.

```bash
./gradlew :micronaut-kool-queue-sample:benchmark
```
