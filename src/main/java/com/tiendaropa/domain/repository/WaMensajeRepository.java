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

    // created_at lo pone la base (DEFAULT now()) y la entidad no lo deja escribir. Los mensajes
    // del historial de WhatsApp Business llegan días o meses después de enviados, así que se
    // corrige su fecha a la original para que el chat quede en orden.
    @Modifying
    @Transactional
    @Query(value = "UPDATE wa_mensajes SET created_at = :fecha WHERE id = :id", nativeQuery = true)
    int actualizarFecha(UUID id, java.time.Instant fecha);

    /** Chats cuyo último mensaje es anterior a {@code limite}: [whatsapp_from, último mensaje, total]. */
    @Query(value = "SELECT whatsapp_from, MAX(created_at) AS ultimo, COUNT(*) AS total FROM wa_mensajes " +
                   "GROUP BY whatsapp_from HAVING MAX(created_at) < :limite ORDER BY ultimo ASC", nativeQuery = true)
    List<Object[]> chatsSinActividadDesde(java.time.Instant limite);

    // Para las notificaciones del panel: el mensaje de cliente más reciente y cuántos hay sin leer.
    Optional<WaMensaje> findFirstByDireccionOrderByCreatedAtDesc(String direccion);

    long countByDireccionAndLeidoFalse(String direccion);

    // Mensajes que llegaron antes de que existiera el cliente (ej. el historial se sincronizó
    // antes que los contactos): se le asignan cuando el cliente se crea.
    @Modifying
    @Transactional
    @Query("UPDATE WaMensaje m SET m.cliente = :cliente WHERE m.whatsappFrom = :whatsappFrom AND m.cliente IS NULL")
    int vincularCliente(com.tiendaropa.domain.model.Cliente cliente, String whatsappFrom);
}
