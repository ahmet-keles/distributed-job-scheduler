# Scheduler load test walkthrough

1. The Compose `load-test` profile adds a worker service that can be scaled independently.
2. Run it with three replicas; together with the scheduler instance, four worker processes compete for jobs.
3. The k6 script defaults to 20 virtual users and 25 jobs per user, for 500 jobs.
4. Its setup phase submits ordinary `noop` jobs through the public REST API.
5. Each test iteration polls one job until it reaches a terminal state.
6. The first attempt's `startedAt` is compared with the job's `createdAt` to estimate claim latency.
7. `claim_latency_ms` reports percentiles, including p95.
8. `jobs_completed` reports successful jobs and its rate gives completed-job throughput.
9. `jobs_failed` must remain zero for the run to pass its threshold.
10. README commands start the isolated load-test profile, run k6 in Docker, and remove its containers and volumes.
11. Set `VUS`, `ITERATIONS_PER_VU`, and `MAX_WAIT_SECONDS` to adjust run size and wait time.
12. **Q: What does the p95 measure?** A: Time from API job creation to the first attempt start.
13. **Q: Why use multiple worker replicas?** A: They exercise competing claims against the same PostgreSQL queue.
14. **Q: Does the throughput include job submission?** A: The counter rate is computed over the k6 run; compare runs with the same queue size and setup.
