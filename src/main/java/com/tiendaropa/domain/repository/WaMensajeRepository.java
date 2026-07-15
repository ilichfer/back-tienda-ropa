package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.WaMensaje;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WaMensajeRepository extends JpaRepository<WaMensaje, UUID> {

    List<WaMensaje> findByClienteIdOrderByCreatedAtDesc(UUID clienteId);

    List<WaMensaje> findByWhatsappFromOrderByCreatedAtDesc(String whatsappFrom);
}
