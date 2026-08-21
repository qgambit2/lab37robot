package com.lab37.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.lab37.model.UploadJob;
import com.lab37.model.UploadJobStatus;

import jakarta.persistence.EntityManager;

@DataJpaTest
class UploadJobRepositoryTest {

	@Autowired
	UploadJobRepository repository;

	@Autowired
	EntityManager entityManager;

	@Test
	void savesAndReadsBackUploadJobRow() {
		UploadJob saved = repository.save(UploadJob.queued("morning.csv"));

		Optional<UploadJob> found = repository.findById(saved.getId());

		assertThat(found).isPresent();
		UploadJob job = found.get();
		assertThat(job.getFileName()).isEqualTo("morning.csv");
		assertThat(job.getStatus()).isEqualTo(UploadJobStatus.QUEUED);
		assertThat(job.getAttempts()).isZero();
		assertThat(job.getError()).isNull();
		assertThat(job.getCreatedAt()).isNotNull();
		assertThat(job.getStartedAt()).isNull();
		assertThat(job.getFinishedAt()).isNull();
	}

	@Test
	void findByIdReturnsEmptyForUnknownJob() {
		assertThat(repository.findById(UUID.randomUUID())).isEmpty();
	}

	@Test
	void claimNextQueuedJobAtomicallyClaimsOnlyQueuedJobs() {
		UploadJob running = repository.save(UploadJob.queued("a.csv"));
		running.setStatus(UploadJobStatus.RUNNING);
		repository.saveAndFlush(running);
		UploadJob queued = repository.saveAndFlush(UploadJob.queued("b.csv"));
		UUID queuedId = queued.getId();
		// detach everything so the claim query result reflects DB state,
		// not the first-level cache
		entityManager.clear();

		Optional<UploadJob> claimed = repository.claimNextQueuedJob(Instant.now().toEpochMilli());

		assertThat(claimed).isPresent();
		UploadJob job = claimed.get();
		assertThat(job.getId()).isEqualTo(queuedId);
		assertThat(job.getStatus()).isEqualTo(UploadJobStatus.RUNNING);
		assertThat(job.getAttempts()).isEqualTo(1);
		assertThat(job.getLockedAt()).isNotNull();
		assertThat(job.getStartedAt()).isNotNull();

		// nothing QUEUED remains, so a second claim comes back empty
		assertThat(repository.claimNextQueuedJob(Instant.now().toEpochMilli())).isEmpty();
	}

	@Test
	void claimNextQueuedJobReturnsEmptyWhenNothingQueued() {
		assertThat(repository.claimNextQueuedJob(Instant.now().toEpochMilli())).isEmpty();
	}
}
