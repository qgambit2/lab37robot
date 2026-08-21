package com.lab37;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
@ConfigurationPropertiesScan
public class OrderProcessingApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderProcessingApplication.class, args);
	}

	/**
	 * Everything in this app runs on UTC — the clock, the yyyyMMddHHmm
	 * dispatch-count buckets, and the configured meal windows (dispatch.windows
	 * are UTC wall times). DB timestamps are stored as millis since epoch
	 * (see InstantMillisConverter), so no stored or computed time depends on
	 * a server's local timezone.
	 */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * DB-backed scheduler lock: guarantees a single dispatcher across app
	 * instances (usingDbTime avoids relying on synchronized server clocks).
	 */
	@Bean
	LockProvider lockProvider(DataSource dataSource) {
		return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
				.withJdbcTemplate(new JdbcTemplate(dataSource))
				.usingDbTime()
				.build());
	}

}
