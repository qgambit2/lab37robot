package com.lab37.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "upload_jobs", indexes = {
		// backs the claim query: WHERE status = 'QUEUED' ORDER BY created_at
		@Index(name = "idx_upload_jobs_status_created", columnList = "status, created_at"),
		// backs the jobs listing: WHERE created_at >= ? ORDER BY created_at
		@Index(name = "idx_upload_jobs_created", columnList = "created_at")})
public class UploadJob {

	@Id
	private UUID id;

	/** Original name of the uploaded file; its bytes live in upload_files. */
	@Column(name = "file_name", nullable = false)
	private String fileName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private UploadJobStatus status;

	@Column(nullable = false)
	private int attempts;

	@Column
	private String error;

	/** How far into the CSV file processing has committed; resume point after a failure. */
	@Column(name = "byte_offset", nullable = false)
	private long byteOffset;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "locked_at")
	private Instant lockedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	protected UploadJob() {
		// for JPA
	}

	private UploadJob(UUID id, String fileName, UploadJobStatus status, Instant createdAt) {
		this.id = id;
		this.fileName = fileName;
		this.status = status;
		this.attempts = 0;
		this.createdAt = createdAt;
	}

	public static UploadJob queued(String fileName) {
		return new UploadJob(UUID.randomUUID(), fileName, UploadJobStatus.QUEUED, Instant.now());
	}

	public UUID getId() {
		return id;
	}

	public String getFileName() {
		return fileName;
	}

	public UploadJobStatus getStatus() {
		return status;
	}

	public void setStatus(UploadJobStatus status) {
		this.status = status;
	}

	public int getAttempts() {
		return attempts;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public long getByteOffset() {
		return byteOffset;
	}

	public void setByteOffset(long byteOffset) {
		this.byteOffset = byteOffset;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLockedAt() {
		return lockedAt;
	}

	public void setLockedAt(Instant lockedAt) {
		this.lockedAt = lockedAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public void setFinishedAt(Instant finishedAt) {
		this.finishedAt = finishedAt;
	}
}
