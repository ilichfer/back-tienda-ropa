package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.Cliente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClienteRepository extends JpaRepository<Cliente, UUID> {

    Optional<Cliente> findByWhatsapp(String whatsapp);

    // Bandeja "Esperando asesor": los que llevan más tiempo esperando primero.
    @Query("SELECT c FROM Cliente c WHERE c.requiereAsesor = true AND (c.esBuzonGuias IS NULL OR c.esBuzonGuias = false) " +
           "ORDER BY c.asesorSolicitadoEn ASC NULLS FIRST")
    List<Cliente> esperandoAsesor();
}
