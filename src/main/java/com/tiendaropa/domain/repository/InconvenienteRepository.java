package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.Inconveniente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InconvenienteRepository extends JpaRepository<Inconveniente, UUID> {

    List<Inconveniente> findAllByOrderByCreatedAtDesc();

    List<Inconveniente> findByEstadoOrderByCreatedAtDesc(String estado);
}
