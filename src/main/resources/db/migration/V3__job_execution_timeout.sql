-- Optional upper bound for one handler attempt. Null keeps the existing
-- unlimited execution behavior for jobs that do not request a timeout.
ALTER TABLE jobs
    ADD COLUMN timeout_seconds BIGINT,
    ADD CONSTRAINT jobs_timeout_seconds_positive CHECK (
        timeout_seconds IS NULL OR timeout_seconds BETWEEN 1 AND 86400
    );
