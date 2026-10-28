# Running-job cancellation walkthrough

1. Cancelling a `PENDING` job still changes it immediately to `CANCELLED`.
2. Cancelling a `RUNNING` job sets a durable `cancel_requested` flag under the row lock.
3. The API response remains `RUNNING` and exposes `cancelRequested: true` while work is stopping.
4. The active worker polls the flag every 250 milliseconds while the handler runs.
5. When requested, the worker interrupts the handler thread and records the attempt as `CANCELLED`.
6. The job becomes terminal `CANCELLED`; cancellation never enters the retry policy.
7. A handler that finishes during the request race is still recorded as cancelled if the request committed first.
8. If the worker lease expires first, the reaper closes the attempt as `CANCELLED` instead of `ABANDONED`.
9. Attempt history adds a `CANCELLED` outcome and remains immutable.
10. Database constraints prevent a cancellation flag on a non-running job.
11. Integration tests cover the API response, durable flag, worker interruption, and attempt outcome.
12. **Q: Does the API kill a thread directly?** A: No, it stores a request that the worker on the owning instance polls.
13. **Q: What if a handler ignores interruption?** A: The row is cancelled, but the handler may continue running until it returns.
14. **Q: Are cancelled jobs retried?** A: No, cancellation is terminal and writes a `CANCELLED` attempt outcome.
