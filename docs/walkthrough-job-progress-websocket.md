# Job progress WebSocket walkthrough

1. Clients connect to `ws://host:8080/ws/jobs/{jobId}` to subscribe to one job.
2. The handler validates the path and rejects subscriptions for unknown jobs.
3. A successful connection immediately receives the job's current persisted snapshot.
4. Events include status, attempts, max attempts, cancellation state, last error, and update time.
5. The handler groups active sessions by job ID to avoid repeating database reads for subscribers to the same job.
6. A scheduled broadcaster reads each subscribed job from PostgreSQL every 250 ms by default.
7. A message is sent only when the persisted snapshot differs from the previous message.
8. Database polling lets subscribers see changes committed by any scheduler instance.
9. The send decorator serializes concurrent writes and bounds each session's buffer.
10. Disconnected or failed sessions are removed from the subscription maps.
11. Client messages are rejected because this socket is a read-only progress stream.
12. Integration tests check the initial message, RUNNING and SUCCEEDED updates, and unknown-job rejection.
13. **Q: Why poll PostgreSQL instead of publishing in-process events?** A: Any instance can commit a state change, so polling keeps each instance's subscribers current.
14. **Q: Does the client need to poll the REST API too?** A: No, the socket sends an initial snapshot and subsequent changes.
15. **Q: How can polling load be reduced?** A: Increase `app.progress.broadcast-interval-ms` to trade update delay for fewer reads.
