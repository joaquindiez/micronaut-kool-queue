# Launch posts for the benchmark article

Replace `[LINK]` with the newsletter URL once the article is live. Until then,
the repository link works as a fallback.

Accounts, verified before use:

- X — **@micronautfw** (The Micronaut Framework, the official account).
- LinkedIn — **Micronaut Framework**, a *showcase page* at
  `linkedin.com/showcase/micronaut/`. When typing `@Micronaut`, pick the entry
  titled "Micronaut Framework"; several unrelated pages match that prefix.

Deliberately **not** tagged: the Rails / Solid Queue side. The post reports
numbers measured against their project, and tagging them into that reads as
picking a fight rather than as a comparison. If they find it, that is a
conversation; if they are summoned to it, it is an argument.

---

## X — single post

Attach `assets/optimisation-history.png`.

> I built a Postgres-backed job queue for @micronautfw and was happy with it.
>
> Then I benchmarked it.
>
> An empty job and a 100 ms job both ran at 10/s. Identical — the signature of a
> system bound by its clock, not its work.
>
> Two fixes later: 463/s.
>
> [LINK]

---

## X — thread (recommended for this story)

**1/** I built a Postgres-backed job queue for @micronautfw. Jobs in, jobs out,
retries, dead-letter. I was happy with it.

Then I wrote a benchmark and found out it had never done what I designed it to
do. 🧵

**2/** The first benchmark reported 30,000 jobs/s. Garbage.

It timed the enqueue phase, then the drain phase. But once the worker keeps up,
jobs drain *while* they are still being enqueued, so you are timing the tail.

End-to-end, or the number means nothing.

**3/** Then the real one: empty job 10.0/s. Job sleeping 100 ms: 9.9/s.

Identical.

When two workloads that should differ don't, you aren't bound by the work. You
are bound by a clock. The poller claimed `limit = 1`, every 0.1s.

**4/** Fix 1 — claim as many jobs as the execution pool has free slots, instead
of a constant: 49.9/s.

Fix 2 — wake the poller the moment a job finishes instead of waiting out the
tick: 463.9/s.

Both ideas taken straight from Solid Queue.

**5/** Fix 3 cut database statements from 603 to 213 per 200 jobs and moved
throughput by nothing measurable — Postgres was on localhost, where a round trip
is free.

Report the deterministic metric, not the one the noise eats.

Write-up + reproducible benchmark: [LINK]

---

## LinkedIn

Attach `assets/optimisation-history.png`.

> I shipped a background job queue for Micronaut. It worked. Then I benchmarked
> it and discovered it had never done what I designed it to do.
>
> The number that gave it away was not a slow one. It was two numbers that
> matched: an empty job ran at 10.0 jobs/second, and a job that sleeps for 100
> milliseconds ran at 9.9. Identical performance from workloads that differ by a
> factor of infinity is the signature of a system bound by its own clock rather
> than by the work it is doing.
>
> The cause was three lines deep in the poller: it claimed one job per tick, and
> it ticked every 0.1 seconds. Ten jobs a second, forever, no matter how much
> concurrency was configured. I had built five lanes and put a one-car toll
> booth at the entrance. I had also read that code many times without seeing it.
>
> Two changes fixed it, both borrowed from Solid Queue in the Rails world:
> claim as many jobs as the execution pool has free slots rather than a
> hard-coded constant, and wake the poller the moment a job finishes instead of
> letting it wait out the rest of its interval. End-to-end throughput went from
> 10 to 464 jobs per second, and for realistic 100 ms jobs it now reaches 94% of
> the ceiling the configured concurrency allows.
>
> The more useful lesson came from the third change. It cut database round trips
> by 2.8x and moved throughput by nothing measurable, because PostgreSQL was
> running on the same machine. That is not a failed optimisation — it is a
> benchmark honestly reporting its own blind spot. I reported the statement
> count, which is deterministic, instead of a throughput delta that JIT noise
> eats.
>
> A benchmark's first job is to find your bugs, not to produce a number for a
> landing page.
>
> Full write-up, the comparison against Solid Queue with its caveats, and a
> reproducible harness: [LINK]
>
> #Micronaut #Kotlin #PostgreSQL #JVM #Performance
