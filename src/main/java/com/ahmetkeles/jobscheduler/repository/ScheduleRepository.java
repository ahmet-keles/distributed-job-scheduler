package com.ahmetkeles.jobscheduler.repository;

import com.ahmetkeles.jobscheduler.domain.Schedule;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    /**
     * Locks up to {@code batchSize} enabled, due schedules for dispatch —
     * the same {@code FOR UPDATE SKIP LOCKED} pattern the job claim uses, so
     * dispatchers on every instance partition due schedules between
     * themselves and each occurrence is materialized exactly once: the
     * winning transaction inserts the job and advances {@code next_run_at}
     * atomically, and the losers never saw the row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value = """
                    SELECT *
                    FROM schedules
                    WHERE enabled
                      AND next_run_at <= :now
                    ORDER BY next_run_at ASC, id ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<Schedule> lockDueSchedules(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    List<Schedule> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
