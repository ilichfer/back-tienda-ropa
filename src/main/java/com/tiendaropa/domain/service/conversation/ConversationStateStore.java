package com.tiendaropa.domain.service.conversation;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaropa.domain.model.WaConversacion;
import com.tiendaropa.domain.repository.WaConversacionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Todo el estado en memoria de las conversaciones de WhatsApp en curso, en un solo lugar.
 *
 * Antes esto eran 3 ConcurrentHashMap sueltos como campos privados de WhatsAppServiceImpl, y
 * cada método que necesitaba tocar el estado de una conversación tenía que acordarse de llamar
 * a mano a persistirConversacion() al final — si un flujo nuevo se le olvidaba, el estado
 * quedaba sin guardar en BD hasta el próximo mensaje que sí pasara por el camino correcto. Acá
 * queda centralizado: quién quiera leer o cambiar el estado de una conversación pasa por esta
 * clase, y quién decide CUÁNDO persistir (WhatsAppServiceImpl.procesarWebhook, en un
 * try/finally que corre siempre, incluso si algo lanza una excepción) sigue siendo explícito,
 * pero ya no depende de que cada punto de salida se acuerde de hacerlo — es un solo lugar.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationStateStore {

    private final WaConversacionRepository conversacionRepo;

    private final ConcurrentHashMap<String, ConversacionCliente> conversaciones = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> ultimaInteraccion = new ConcurrentHashMap<>();

    // Cuándo se le avisó por última vez a cada cliente (que ya requiere asesor) que un humano
    // lo va a atender, para no repetir ese aviso en cada mensaje que mande mientras espera.
    private final ConcurrentHashMap<String, Instant> ultimoAvisoAsesor = new ConcurrentHashMap<>();

    // Serializa ConversacionCliente completo (campos sin getters incluidos) para poder
    // guardar/recuperar el estado de la conversación en la tabla wa_conversaciones.
    private static final ObjectMapper CONVERSACION_MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    public ConversacionCliente get(String from) {
        return conversaciones.get(from);
    }

    public void put(String from, ConversacionCliente conv) {
        conversaciones.put(from, conv);
    }

    public void remove(String from) {
        conversaciones.remove(from);
    }

    public boolean existe(String from) {
        return conversaciones.containsKey(from);
    }

    public Instant getUltimaInteraccion(String from) {
        return ultimaInteraccion.get(from);
    }

    public void marcarInteraccion(String from) {
        ultimaInteraccion.put(from, Instant.now());
    }

    public Instant getUltimoAvisoAsesor(String from) {
        return ultimoAvisoAsesor.get(from);
    }

    public void marcarAvisoAsesor(String from) {
        ultimoAvisoAsesor.put(from, Instant.now());
    }

    /** Limpia el estado en memoria y persistido de {@code from} por completo (borrar chat). */
    public void limpiarTodo(String from) {
        conversaciones.remove(from);
        ultimaInteraccion.remove(from);
        ultimoAvisoAsesor.remove(from);
        if (conversacionRepo.existsById(from)) {
            conversacionRepo.deleteById(from);
        }
    }

    /**
     * Si no hay en memoria la conversación de {@code from} (por ejemplo, justo después de un
     * reinicio del backend), la recupera de la tabla wa_conversaciones para que el cliente no
     * tenga que volver a empezar el flujo desde cero.
     */
    public void recuperarSiNecesario(String from) {
        if (conversaciones.containsKey(from)) return;
        conversacionRepo.findById(from).ifPresent(entidad -> {
            try {
                var conv = CONVERSACION_MAPPER.readValue(entidad.getDatos(), ConversacionCliente.class);
                conversaciones.put(from, conv);
                ultimaInteraccion.put(from, entidad.getUpdatedAt() != null ? entidad.getUpdatedAt() : Instant.now());
                log.info("[CONV] Conversación de {} recuperada de base de datos (flujo={}, paso={})",
                        from, conv.flujo, conv.paso);
            } catch (Exception e) {
                log.warn("[CONV] No se pudo recuperar conversación persistida de {}: {}", from, e.getMessage());
            }
        });
    }

    /**
     * Refleja en la tabla wa_conversaciones el estado actual en memoria de la conversación de
     * {@code from}: la crea o actualiza si sigue activa, o la borra si ya se finalizó/canceló.
     */
    public void persistir(String from) {
        try {
            var conv = conversaciones.get(from);
            if (conv == null) {
                if (conversacionRepo.existsById(from)) conversacionRepo.deleteById(from);
                return;
            }
            var entidad = conversacionRepo.findById(from).orElseGet(() -> {
                var nueva = new WaConversacion();
                nueva.setWhatsappFrom(from);
                return nueva;
            });
            entidad.setFlujo(conv.flujo);
            entidad.setPaso(conv.paso);
            entidad.setDatos(CONVERSACION_MAPPER.writeValueAsString(conv));
            entidad.setUpdatedAt(Instant.now());
            conversacionRepo.save(entidad);
        } catch (Exception e) {
            log.warn("[CONV] No se pudo persistir conversación de {}: {}", from, e.getMessage());
        }
    }
}
