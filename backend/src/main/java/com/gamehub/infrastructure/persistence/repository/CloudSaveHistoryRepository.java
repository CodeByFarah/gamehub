package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.CloudSaveHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CloudSaveHistoryRepository extends JpaRepository<CloudSaveHistoryEntity, UUID> {

    List<CloudSaveHistoryEntity> findByCloudSaveIdOrderByVersionDesc(UUID cloudSaveId);
}
