package com.tiendaropa.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.WaMensaje;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.CoexistenciaService;
import com.tiendaropa.domain.service.whatsapp.WhatsAppCloudClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoexistenciaServiceImpl implements CoexistenciaService {

    private final WhatsAppCloudClient cloud;
    private final WaMensajeRepository mensajeRepo;
    private final ClienteRepository clienteRepo;

    /** Lo que se guarda de un mensaje de WhatsApp, sea cual sea su tipo. */
    private record Contenido(String tipo, String texto, String mediaId, String mimeType) {}

    // ── Historial ─────────────────────────────────────────────────────────────────────────

    @Override
    public void procesarHistorial(JsonNode value) {
        for (var bloque : value.path("history")) {
            if (bloque.has("errors")) {
                // Ej. 2593109: el negocio no aceptó compartir el historial desde la app.
                var err = bloque.path("errors").path(0);
                log.warn("[COEXISTENCIA] Meta no entregó el historial: {} ({})",
                        err.path("title").asText("sin detalle"), err.path("code").asText("?"));
                continue;
            }

            var meta = bloque.path("metadata");
            int guardados = 0;
            for (var hilo : bloque.path("threads")) {
                var numeroCliente = soloDigitos(hilo.path("id").asText(""));
                if (numeroCliente.isEmpty()) continue;
                var cliente = clienteRepo.findByWhatsapp(numeroCliente).orElse(null);
                for (var msg : hilo.path("messages")) {
                    if (guardarHistorico(numeroCliente, cliente, msg)) guardados++;
                }
            }
            // phase 0 = último día, 1 = hasta 90 días, 2 = hasta 180 días; progress 100 = terminó.
            log.info("[COEXISTENCIA] Historial fase {} bloque {} ({}%): {} mensajes nuevos guardados",
                    meta.path("phase").asText("?"), meta.path("chunk_order").asText("?"),
                    meta.path("progress").asText("?"), guardados);
        }
    }

    /** Devuelve true si guardó o completó el mensaje, false si ya estaba o no aplica. */
    private boolean guardarHistorico(String numeroCliente, Cliente cliente, JsonNode msg) {
        var waId = msg.path("id").asText("");
        var contenido = contenidoDe(msg);
        if (waId.isEmpty() || contenido == null) return false;

        var existente = mensajeRepo.findByWaMessageId(waId).orElse(null);
        if (existente != null) {
            // Las fotos/archivos del historial llegan primero como "media_placeholder" y el
            // archivo real en un webhook posterior con el mismo id: se completa el mensaje.
            if ("media_placeholder".equals(existente.getTipo()) && contenido.mediaId() != null) {
                existente.setTipo(contenido.tipo());
                existente.setContenido(contenido.texto());
                existente.setMediaId(contenido.mediaId());
                existente.setMimeType(contenido.mimeType());
                mensajeRepo.save(existente);
                return true;
            }
            return false;
        }

        var entrante = numeroCliente.equals(soloDigitos(msg.path("from").asText("")));
        var estado = msg.path("history_context").path("status").asText("");
        var guardado = guardar(WaMensaje.builder()
                .whatsappFrom(numeroCliente)
                .cliente(cliente)
                .contenido(contenido.texto())
                .tipo(contenido.tipo())
                .direccion(entrante ? "ENTRADA" : "SALIDA")
                .waMessageId(waId)
                .contextWaMessageId(textoONull(msg.path("context").path("id")))
                .mediaId(contenido.mediaId())
                .mimeType(contenido.mimeType())
                // Historial viejo: no debe aparecer como cientos de mensajes sin leer.
                .leido(true)
                .estadoEntrega(entrante || estado.isEmpty() ? null : estado.toLowerCase())
                .build(), msg);
        return guardado;
    }

    // ── Contactos ─────────────────────────────────────────────────────────────────────────

    @Override
    public void procesarContactos(JsonNode value) {
        for (var cambio : value.path("state_sync")) {
            if (!"contact".equals(cambio.path("type").asText())) continue;
            var contacto = cambio.path("contact");
            var numero = soloDigitos(contacto.path("phone_number").asText(""));
            if (numero.isEmpty()) continue;

            if ("remove".equals(cambio.path("action").asText())) {
                // Borrarlo del celular no borra al cliente del panel: puede tener pedidos y cuentas.
                log.info("[COEXISTENCIA] Contacto {} eliminado del celular (se conserva en el panel)", numero);
                continue;
            }

            var nombre = contacto.path("full_name").asText("").trim();
            if (nombre.isEmpty()) nombre = contacto.path("first_name").asText("").trim();
            if (nombre.length() > 120) nombre = nombre.substring(0, 120);

            var cliente = clienteRepo.findByWhatsapp(numero).orElse(null);
            if (cliente == null) {
                cliente = clienteRepo.save(Cliente.builder()
                        .whatsapp(numero)
                        .nombre(nombre.isEmpty() ? null : nombre)
                        .build());
                log.info("[COEXISTENCIA] Cliente creado desde los contactos del celular: {} ({})", nombre, numero);
            } else if ((cliente.getNombre() == null || cliente.getNombre().isBlank()) && !nombre.isEmpty()) {
                // Solo se completa si no tenía nombre: no se pisa el que el equipo puso en el panel.
                cliente.setNombre(nombre);
                clienteRepo.save(cliente);
            }
            mensajeRepo.vincularCliente(cliente, numero);
        }
    }

    // ── Ecos (respuestas desde el celular) ────────────────────────────────────────────────

    @Override
    public String registrarEco(JsonNode eco) {
        var numeroCliente = soloDigitos(eco.path("to").asText(""));
        var waId = eco.path("id").asText("");
        var contenido = contenidoDe(eco);
        if (numeroCliente.isEmpty() || waId.isEmpty() || contenido == null) return null;
        if (mensajeRepo.findByWaMessageId(waId).isPresent()) return numeroCliente;

        var cliente = clienteRepo.findByWhatsapp(numeroCliente).orElse(null);
        guardar(WaMensaje.builder()
                .whatsappFrom(numeroCliente)
                .cliente(cliente)
                .contenido(contenido.texto())
                .tipo(contenido.tipo())
                .direccion("SALIDA")
                .waMessageId(waId)
                .contextWaMessageId(textoONull(eco.path("context").path("id")))
                .mediaId(contenido.mediaId())
                .mimeType(contenido.mimeType())
                .leido(true)
                .estadoEntrega("sent")
                .build(), eco);

        // Igual que al responder desde el panel: si el cliente estaba marcado como "requiere
        // asesor", ya lo atendió una persona.
        if (cliente != null && Boolean.TRUE.equals(cliente.getRequiereAsesor())) {
            cliente.setRequiereAsesor(false);
            clienteRepo.save(cliente);
        }
        log.info("[COEXISTENCIA] Respuesta desde el celular a {} registrada ({})", numeroCliente, contenido.tipo());
        return numeroCliente;
    }

    // ── Pedir sincronización ──────────────────────────────────────────────────────────────

    @Override
    public JsonNode solicitarSincronizacion(String syncType) {
        try {
            var respuesta = cloud.solicitarSincronizacion(syncType);
            log.info("[COEXISTENCIA] Sincronización '{}' solicitada: {}", syncType, respuesta);
            return respuesta;
        } catch (WebClientResponseException e) {
            log.error("[COEXISTENCIA] Meta rechazó la sincronización '{}': {}", syncType, e.getResponseBodyAsString());
            throw new IllegalStateException("Meta rechazó la sincronización: " + e.getResponseBodyAsString(), e);
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────────────────

    private boolean guardar(WaMensaje mensaje, JsonNode original) {
        try {
            var guardado = mensajeRepo.save(mensaje);
            var fecha = fechaDe(original);
            if (fecha != null) mensajeRepo.actualizarFecha(guardado.getId(), fecha);
            return true;
        } catch (DataIntegrityViolationException dup) {
            // Dos bloques del historial procesándose a la vez con el mismo mensaje: el índice
            // único de wa_message_id ya lo tiene.
            return false;
        }
    }

    /** Traduce un mensaje de WhatsApp a lo que guarda el panel; null si no se guarda (reacciones). */
    private static Contenido contenidoDe(JsonNode m) {
        var tipo = m.path("type").asText("unknown");
        switch (tipo) {
            case "text":
                return new Contenido("text", m.path("text").path("body").asText(""), null, null);
            case "image", "video", "document", "audio", "sticker": {
                var media = m.path(tipo);
                var caption = media.path("caption").asText("");
                if (caption.isBlank() && "document".equals(tipo)) caption = media.path("filename").asText("");
                return new Contenido(tipo, caption.isBlank() ? "[" + tipo + "]" : caption,
                        textoONull(media.path("id")), textoONull(media.path("mime_type")));
            }
            case "media_placeholder":
                // Foto o archivo cuyo contenido llega después (o nunca, si tiene más de 14 días).
                return new Contenido("media_placeholder", "[archivo del historial]", null, null);
            case "location": {
                var loc = m.path("location");
                var detalle = String.join(" - ", java.util.stream.Stream.of(
                        loc.path("name").asText(""), loc.path("address").asText(""))
                        .filter(s -> !s.isBlank()).toList());
                return new Contenido("location", detalle.isEmpty() ? "📍 Ubicación" : "📍 " + detalle, null, null);
            }
            case "contacts":
                return new Contenido("contacts", "[contacto compartido]", null, null);
            case "button":
                return new Contenido("text", m.path("button").path("text").asText("[botón]"), null, null);
            case "interactive": {
                var inter = m.path("interactive");
                var titulo = inter.path("button_reply").path("title").asText(
                        inter.path("list_reply").path("title").asText("[respuesta interactiva]"));
                return new Contenido("text", titulo, null, null);
            }
            case "reaction":
                return null;
            default:
                return new Contenido("unsupported", "[mensaje no soportado: " + tipo + "]", null, null);
        }
    }

    private static Instant fechaDe(JsonNode m) {
        try {
            var segundos = Long.parseLong(m.path("timestamp").asText(""));
            return segundos > 0 ? Instant.ofEpochSecond(segundos) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String soloDigitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static String textoONull(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }
}
