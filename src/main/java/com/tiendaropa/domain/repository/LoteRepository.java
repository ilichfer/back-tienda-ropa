package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.Lote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoteRepository extends JpaRepository<Lote, UUID> {

    List<Lote> findByActivoTrueOrderByCreatedAtDesc();

    List<Lote> findAllByOrderByCreatedAtDesc();
}
