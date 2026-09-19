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

Attach `assets/optimisation-history.png`. Tag **Micronaut Framework** on the
paragraph that names it.

Angle: the method first, the library as the case study — LinkedIn's audience is
mostly not a JVM audience, and the benchmarking lesson travels where "my Kotlin
queue got faster" does not. It also withholds the fix, so the click is the only
way to get it. (The earlier full-story version is in git history, commit
`ed0657c`.)

> Two numbers in a benchmark matched, and that was the bug.
>
> An empty job ran at 10.0 jobs per second. A job that sleeps for 100
> milliseconds ran at 9.9 jobs per second. Two workloads that differ by a factor
> of infinity, performing identically. That does not mean a system is
> consistent. It means you are not measuring the work at all — you are measuring
> a clock.
>
> That was my own library: a Postgres-backed job queue for Micronaut that I had
> shipped, used, and read the source of many times without ever seeing it.
>
> Three things I now check before I trust a benchmark.
>
> 1. Does the headline metric survive the system getting faster? Mine didn't. It
> timed the enqueue phase, then the drain phase, and reported 30,000 jobs/s —
> because once the worker keeps up, the queue drains while it is still being
> filled. The metric broke precisely when the system started working properly.
>
> 2. Do two workloads that should differ actually differ? This is the cheapest
> bug detector I know, and it is what caught mine. Run something trivial and
> something slow. If the numbers agree, something other than your code is
> setting the pace.
>
> 3. Can the metric you report even detect the change you made? One of my fixes
> cut database round trips by 2.8x and moved throughput by nothing measurable,
> because PostgreSQL was running on the same machine. So I reported the
> statement count, which is deterministic, rather than a throughput delta that
> measurement noise eats.
>
> None of this is exotic. It is the difference between a benchmark that flatters
> you and one that finds your bugs — which, once I let it, took that queue from
> 10 to 464 jobs a second.
>
> The full story, the two fixes, and an honest comparison against Solid Queue:
> [LINK]
>
> #Benchmarking #Performance #PostgreSQL #Micronaut #SoftwareEngineering
