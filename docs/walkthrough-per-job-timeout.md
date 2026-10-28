# Per-job execution timeout walkthrough

1. Job creation accepts an optional `timeoutSeconds` value between 1 and 86400.
2. The value is stored in the `jobs.timeout_seconds` column; null means no limit.
3. `GET /api/jobs` responses include the configured timeout.
4. Claiming a job copies its timeout into the detached `ClaimedJob` snapshot.
5. The worker starts a timer only for claims that have a timeout configured.
6. If the handler finishes first, its normal result is recorded and the timer is cancelled.
7. If the timer fires first, the worker interrupts the handler thread.
8. The timeout records a failed attempt with a clear timeout error message.
9. Existing bounded retries and backoff decide whether the job runs again or becomes `FAILED`.
10. An atomic guard ensures the timeout and handler result cannot both be recorded.
11. The existing worker and attempt number fence still rejects stale completions.
12. A handler that ignores interruption can continue running, so handlers should respond to interruption.
13. `./mvnw test` exercises an on-time success, timeout retry, and terminal timeout failure.
14. **Q: Is the timeout global?** A: No, it is selected per job at submission.
15. **Q: What if the handler ignores interruption?** A: Its late completion is fenced; it can still occupy a worker thread.
16. **Q: Does timeout bypass retry policy?** A: No, it is recorded as an ordinary failed attempt.
