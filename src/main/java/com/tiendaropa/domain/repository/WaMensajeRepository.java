package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.WaMensaje;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaMensajeRepository extends JpaRepository<WaMensaje, UUID> {

    @Query("SELECT m FROM WaMensaje m LEFT JOIN FETCH m.cliente ORDER BY m.createdAt DESC")
    List<WaMensaje> findAllConCliente();

    List<WaMensaje> findByClienteIdOrderByCreatedAtDesc(UUID clienteId);

    @Query("SELECT m FROM WaMensaje m LEFT JOIN FETCH m.cliente WHERE m.whatsappFrom = :whatsappFrom ORDER BY m.createdAt DESC")
    List<WaMensaje> findByWhatsappFromConCliente(String whatsappFrom);

    Optional<WaMensaje> findFirstByWhatsappFromAndDireccionOrderByCreatedAtDesc(String whatsappFrom, String direccion);
}
