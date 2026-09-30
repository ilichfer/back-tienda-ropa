package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.WaMensaje;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

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

    // Los últimos mensajes entrantes de un número, para mirar qué escribió el cliente justo
    // antes de una foto (ej. "no me llegó esta prenda" y luego la foto).
    List<WaMensaje> findTop3ByWhatsappFromAndDireccionOrderByCreatedAtDesc(String whatsappFrom, String direccion);

    // La foto original de un cargo/abono (por su media id de Meta), para volver a citarla.
    Optional<WaMensaje> findFirstByMediaId(String mediaId);

    // Para actualizar el estado de entrega (sent/delivered/read/failed) que llega por el
    // webhook de "statuses" de Meta, referenciando el mensaje saliente por su wa_message_id.
    Optional<WaMensaje> findByWaMessageId(String waMessageId);

    boolean existsByWhatsappFrom(String whatsappFrom);

    @Modifying
    @Transactional
    @Query("UPDATE WaMensaje m SET m.leido = true WHERE m.whatsappFrom = :whatsappFrom AND m.direccion = 'ENTRADA'")
    int marcarLeidas(String whatsappFrom);

    @Modifying
    @Transactional
    @Query("DELETE FROM WaMensaje m WHERE m.whatsappFrom = :whatsappFrom")
    int deleteByWhatsappFrom(String whatsappFrom);
}
