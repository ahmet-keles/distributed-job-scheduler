ALTER TABLE jobs
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT jobs_cancel_requested_status_check
        CHECK (NOT cancel_requested OR status = 'RUNNING');

ALTER TABLE job_attempts
    DROP CONSTRAINT job_attempts_outcome_check,
    ADD CONSTRAINT job_attempts_outcome_check CHECK (
        outcome IS NULL
        OR outcome IN ('SUCCEEDED', 'FAILED', 'ABANDONED', 'CANCELLED')
    );
