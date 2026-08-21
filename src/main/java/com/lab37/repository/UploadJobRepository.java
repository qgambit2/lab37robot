package com.lab37.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.lab37.model.UploadJob;

public interface UploadJobRepository extends JpaRepository<UploadJob, UUID> {

	/**
	 * Atomically claims the oldest QUEUED job in a single statement: flips it
	 * to RUNNING, stamps locked_at/started_at, increments attempts, and
	 * returns the updated row. FOR UPDATE SKIP LOCKED in the subselect makes
	 * concurrent pollers skip a row mid-claim, and the outer status guard
	 * re-checks it, so no two workers can claim the same job — without any
	 * wrapping transaction. (Postgres spelling: UPDATE … RETURNING *;
	 * FINAL TABLE is H2's data-change delta table equivalent.)
	 */
	@Query(value = """
			SELECT * FROM FINAL TABLE (
			    UPDATE upload_jobs
			    SET status = 'RUNNING',
			        locked_at = :nowMillis,
			        started_at = :nowMillis,
			        attempts = attempts + 1
			    WHERE status = 'QUEUED'
			      AND id = (SELECT id FROM upload_jobs
			                WHERE status = 'QUEUED'
			                ORDER BY created_at
			                FETCH FIRST 1 ROWS ONLY
			                FOR UPDATE SKIP LOCKED)
			)
			""", nativeQuery = true)
	Optional<UploadJob> claimNextQueuedJob(@Param("nowMillis") long nowMillis);

	/**
	 * Same atomic claim in PostgreSQL's dialect (UPDATE … RETURNING — H2 does
	 * not support RETURNING even in its PostgreSQL compatibility mode, hence
	 * two variants selected by ingest.claim-dialect). NOTE: not exercised by
	 * the H2-based test suite; verify against a live Postgres before relying
	 * on it.
	 */
	@Query(value = """
			UPDATE upload_jobs
			SET status = 'RUNNING',
			    locked_at = :nowMillis,
			    started_at = :nowMillis,
			    attempts = attempts + 1
			WHERE status = 'QUEUED'
			  AND id = (SELECT id FROM upload_jobs
			            WHERE status = 'QUEUED'
			            ORDER BY created_at
			            FOR UPDATE SKIP LOCKED
			            LIMIT 1)
			RETURNING *
			""", nativeQuery = true)
	Optional<UploadJob> claimNextQueuedJobPostgres(@Param("nowMillis") long nowMillis);

	/**
	 * Jobs listing backing GET /v1/jobs, newest first; createdAfter (null =
	 * no filter) means "created at or after".
	 */
	@Query("""
			select j from UploadJob j
			where (:createdAfter is null or j.createdAt >= :createdAfter)
			order by j.createdAt desc
			""")
	List<UploadJob> search(@Param("createdAfter") Instant createdAfter);
}
