ALTER TABLE jobs
    ADD COLUMN dead_lettered_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN redrive_attempt_offset INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT jobs_redrive_attempt_offset_nonnegative
        CHECK (redrive_attempt_offset >= 0 AND redrive_attempt_offset <= attempts),
    ADD CONSTRAINT jobs_dead_letter_status_check
        CHECK (dead_lettered_at IS NULL OR status = 'FAILED');

-- Existing exhausted jobs also belong to the queue after this migration.
UPDATE jobs
SET dead_lettered_at = updated_at
WHERE status = 'FAILED';

CREATE INDEX idx_jobs_dead_lettered
    ON jobs (dead_lettered_at DESC)
    WHERE dead_lettered_at IS NOT NULL;
