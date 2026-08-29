-- Milestone 2: job priority and recurring schedules. The coordination model
-- is unchanged — schedules only materialize ordinary jobs, which then flow
-- through the existing claim/lease/retry machinery untouched.

-- === jobs: priority and schedule lineage ====================================
ALTER TABLE jobs
    ADD COLUMN priority INT NOT NULL DEFAULT 0,
    -- Set only on jobs materialized from a recurring schedule.
    ADD COLUMN schedule_id UUID,
    -- The nominal cron occurrence this job represents (the schedule's
    -- next_run_at at dispatch time), distinct from scheduled_at, which is
    -- when the job became claimable.
    ADD COLUMN scheduled_for TIMESTAMP(6) WITH TIME ZONE;

-- Claim scan now orders by priority first; the index matches the claim
-- query's predicate and ORDER BY exactly and still holds only the runnable
-- backlog.
DROP INDEX idx_jobs_claimable;
CREATE INDEX idx_jobs_claimable
    ON jobs (priority DESC, scheduled_at ASC, id ASC)
    WHERE status = 'PENDING';

-- === schedules: recurring definitions =======================================
CREATE TABLE schedules (
    id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    job_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    -- Spring 6-field cron expression, evaluated in UTC.
    cron VARCHAR(120) NOT NULL,
    priority INT NOT NULL,
    max_attempts INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    -- The next nominal occurrence to materialize; maintained by the
    -- dispatcher (advance on fire) and by resume (recompute from now).
    next_run_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT schedules_pkey PRIMARY KEY (id),
    CONSTRAINT schedules_max_attempts_positive CHECK (max_attempts > 0)
);

-- Dispatch scan: enabled schedules by due time; partial, mirroring the
-- jobs claim index pattern.
CREATE INDEX idx_schedules_due
    ON schedules (next_run_at ASC, id ASC)
    WHERE enabled;

-- === lineage from jobs back to their schedule ===============================
ALTER TABLE jobs
    ADD CONSTRAINT fk_jobs_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules (id)
        ON DELETE SET NULL;

-- Backstop against double-materialization: one job per (schedule,
-- occurrence). The dispatcher's SKIP LOCKED claim plus its single
-- insert-and-advance transaction already prevent duplicates; this makes a
-- future logic regression fail loudly instead of firing twice.
CREATE UNIQUE INDEX uq_jobs_schedule_occurrence
    ON jobs (schedule_id, scheduled_for)
    WHERE schedule_id IS NOT NULL;

-- "Recent executions of a schedule" listing.
CREATE INDEX idx_jobs_schedule_created
    ON jobs (schedule_id, created_at DESC)
    WHERE schedule_id IS NOT NULL;
