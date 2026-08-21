package com.lab37.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.lab37.model.ApiPolling;

public interface ApiPollingRepository extends JpaRepository<ApiPolling, Integer> {
}
