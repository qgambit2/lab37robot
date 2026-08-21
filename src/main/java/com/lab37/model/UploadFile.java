package com.lab37.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Raw bytes of an uploaded CSV, stored in the DB (not the local filesystem)
 * so any app instance can process any job — the DB is the only shared state
 * between boxes. Kept in its own table so the frequently-scanned upload_jobs
 * rows stay small. Survey CSVs are tiny; truly large files would move to
 * object storage (S3) instead.
 */
@Entity
@Table(name = "upload_files")
public class UploadFile {

	@Id
	@Column(name = "job_id")
	private UUID jobId;

	// bytea works on both targets: real PostgreSQL and H2 in PostgreSQL mode
	// (whose parser rejects the BLOB type Hibernate would otherwise emit)
	@Column(nullable = false, columnDefinition = "bytea")
	private byte[] content;

	protected UploadFile() {
		// for JPA
	}

	public static UploadFile of(UUID jobId, byte[] content) {
		UploadFile file = new UploadFile();
		file.jobId = jobId;
		file.content = content;
		return file;
	}

	public UUID getJobId() {
		return jobId;
	}

	public byte[] getContent() {
		return content;
	}
}
