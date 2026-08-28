CREATE TABLE jobs (
    id UUID NOT NULL,
    type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    scheduled_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    attempts INT NOT NULL,
    max_attempts INT NOT NULL,
    worker_id VARCHAR(100),
    lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
    last_error TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT jobs_pkey PRIMARY KEY (id),
    CONSTRAINT jobs_status_check CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT jobs_max_attempts_positive CHECK (max_attempts > 0),
    CONSTRAINT jobs_attempts_nonnegative CHECK (attempts >= 0),
    -- A RUNNING job always names its owner and carries a live-or-expired
    -- lease; any other status carries neither. The claim/complete/reap
    -- transitions maintain this in Java; the schema refuses rows that bypass
    -- them.
    CONSTRAINT jobs_claim_matches_status CHECK (
        (status = 'RUNNING' AND worker_id IS NOT NULL
             AND lease_expires_at IS NOT NULL)
        OR (status <> 'RUNNING' AND worker_id IS NULL
             AND lease_expires_at IS NULL)
    )
);

-- Claim scan: PENDING jobs due first. Partial, matching the claim query's
-- predicate and ORDER BY exactly, and holding only the runnable backlog.
CREATE INDEX idx_jobs_claimable
    ON jobs (scheduled_at, id)
    WHERE status = 'PENDING';

-- Reaper scan: RUNNING jobs by lease deadline; also partial.
CREATE INDEX idx_jobs_running_lease
    ON jobs (lease_expires_at, id)
    WHERE status = 'RUNNING';

-- Heartbeat: all RUNNING jobs of one worker.
CREATE INDEX idx_jobs_worker
    ON jobs (worker_id)
    WHERE status = 'RUNNING';

-- Listing: newest first, optionally by status.
CREATE INDEX idx_jobs_status_created
    ON jobs (status, created_at DESC);

CREATE TABLE job_attempts (
    id UUID NOT NULL,
    job_id UUID NOT NULL,
    attempt_number INT NOT NULL,
    worker_id VARCHAR(100) NOT NULL,
    started_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP(6) WITH TIME ZONE,
    outcome VARCHAR(20),
    error TEXT,

    CONSTRAINT job_attempts_pkey PRIMARY KEY (id),
    CONSTRAINT fk_job_attempts_job
        FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    -- One history row per (job, attempt): the claim transaction that starts
    -- attempt N is the only writer of row N.
    CONSTRAINT job_attempts_job_attempt_unique
        UNIQUE (job_id, attempt_number),
    CONSTRAINT job_attempts_attempt_number_positive
        CHECK (attempt_number > 0),
    CONSTRAINT job_attempts_outcome_check CHECK (
        outcome IS NULL
        OR outcome IN ('SUCCEEDED', 'FAILED', 'ABANDONED')
    ),
    -- An open attempt has no finish time; a closed one always has both.
    CONSTRAINT job_attempts_closed_consistent CHECK (
        (outcome IS NULL AND finished_at IS NULL)
        OR (outcome IS NOT NULL AND finished_at IS NOT NULL)
    )
);

CREATE INDEX idx_job_attempts_job
    ON job_attempts (job_id, attempt_number);
