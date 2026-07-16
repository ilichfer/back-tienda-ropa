package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.SolicitudEnvio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SolicitudEnvioRepository extends JpaRepository<SolicitudEnvio, UUID> {

    List<SolicitudEnvio> findByEstadoOrderByCreatedAtDesc(String estado);

    List<SolicitudEnvio> findAllByOrderByCreatedAtDesc();

    Optional<SolicitudEnvio> findFirstByWhatsappAndEstadoOrderByCreatedAtDesc(String whatsapp, String estado);
}
