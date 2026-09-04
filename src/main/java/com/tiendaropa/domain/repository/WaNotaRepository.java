package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.WaNota;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WaNotaRepository extends JpaRepository<WaNota, UUID> {

    List<WaNota> findByWhatsappFromOrderByCreatedAtDesc(String whatsappFrom);
}
