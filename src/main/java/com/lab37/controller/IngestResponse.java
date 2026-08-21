package com.lab37.controller;

import java.util.Map;
import java.util.UUID;

import com.lab37.model.UploadJob;
import com.lab37.model.UploadJobStatus;

public record IngestResponse(UUID jobId, UploadJobStatus status, Map<String, String> links) {

	public static IngestResponse of(UploadJob job) {
		return new IngestResponse(job.getId(), job.getStatus(),
				Map.of("self", "/v1/jobs/" + job.getId()));
	}
}
