# Micronaut-Kool-Queue: DB-based queuing backend for Micronaut

Kool Queue  is a DB-based queuing backend for Micronaut Framework, designed with simplicity and performance in mind.

Kool Queue can be used with SQL databases such as PostgreSQ, and it leverages the FOR UPDATE SKIP LOCKED clause, if available, to avoid blocking and waiting on locks when polling jobs.


# Installation


TODO


# High performance requirements

Koll Queue was designed for the highest throughput when used with MySQL 8+ or PostgreSQL 9.5+, as they support FOR UPDATE SKIP LOCKED.
You can use it with older versions, but in that case, you might run into lock waits if you run multiple workers for the same queue.
You can also use it with SQLite on smaller applications.

# Configuration


TODO


# Inspiration

Kool Queue has been inspired by [Solid Queue](https://github.com/rails/solid_queue) and Rails.
We recommend checking out these projects as they're great examples from which we've learnt a lot.

#License

The library is available as open source under the terms of the [APACHE 2.0](./LICENSE)
