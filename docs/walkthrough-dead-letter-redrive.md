# Dead-letter queue and redrive walkthrough

1. A job keeps its existing `FAILED` status when it exhausts its retry budget.
2. The same transaction records `dead_lettered_at` to mark it in the dead-letter queue.
3. The marker is set whether exhaustion comes from a handler failure or an expired lease.
4. A database index makes the dead-letter listing efficient as the jobs table grows.
5. `GET /api/jobs/dead-letter` returns the newest queued jobs with their last error.
6. `POST /api/jobs/{id}/redrive` locks the row before changing its state.
7. Only a currently dead-lettered job can be redriven; other states return `409`.
8. Redrive clears the queue marker and makes the job eligible immediately.
9. Attempt counts and history remain monotonic, preserving the unique attempt-number key.
10. A stored offset gives the redriven job a fresh budget of its configured `maxAttempts`.
11. If the new budget also runs out, the job returns to the queue with a new timestamp.
12. The migration also marks previously exhausted jobs as dead-lettered.
13. **Q: Why keep status `FAILED`?** A: Existing failure semantics stay intact while the timestamp marks queue membership.
14. **Q: Are old attempt rows rewritten on redrive?** A: No, history stays immutable and new attempt numbers continue increasing.
15. **Q: How many attempts does redrive allow?** A: One new retry budget equal to the job's configured `maxAttempts`.
