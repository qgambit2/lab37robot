package com.lab37.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.lab37.model.UploadFile;

public interface UploadFileRepository extends JpaRepository<UploadFile, UUID> {
}
