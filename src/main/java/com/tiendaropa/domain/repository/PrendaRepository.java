package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.Prenda;
import com.tiendaropa.domain.model.enums.EstadoPrenda;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PrendaRepository extends JpaRepository<Prenda, UUID> {

    List<Prenda> findByLoteIdOrderByCreatedAtAsc(UUID loteId);

    long countByLoteId(UUID loteId);

    long countByLoteIdAndEstado(UUID loteId, EstadoPrenda estado);
}
