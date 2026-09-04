package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.WaBroadcast;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WaBroadcastRepository extends JpaRepository<WaBroadcast, UUID> {

    List<WaBroadcast> findAllByOrderByCreatedAtDesc();
}
