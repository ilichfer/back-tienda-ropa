package com.tiendaropa.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.WaMensaje;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.SolicitudEnvioRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.EnvioService;
import com.tiendaropa.domain.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppServiceImpl implements WhatsAppService {

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.access-token}")
    private String accessToken;

    private final WebClient whatsappWebClient;

    private final WaMensajeRepository mensajeRepo;
    private final ClienteRepository   clienteRepo;
    private final EnvioService envioService;
    private final SolicitudEnvioRepository solicitudRepo;

    private static class EnvioConversation {
        String nombre;
        String telefono;
        String cedula;
        String direccion;
        String ciudad;
        String barrio;
        int step;
    }

    private final ConcurrentHashMap<String, EnvioConversation> conversacionesEnvio = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> ultimaInteraccion = new ConcurrentHashMap<>();

    @Override
    public void procesarWebhook(JsonNode payload) {
        try {
            var entry    = payload.get("entry").get(0);
            var change   = entry.get("changes").get(0).get("value");
            var messages = change.get("messages");

            if (messages == null || messages.isEmpty()) return;

            var msg  = messages.get(0);
            var from = msg.get("from").asText();
            var waId = msg.get("id").asText();
            var type = msg.get("type").asText();

            String contenido;
            String tipo;
            String mediaId = "";
            String mimeType = "";

            switch (type) {
                case "text" -> {
                    contenido = msg.get("text").get("body").asText();
                    tipo = "text";
                    log.info("WA texto [{}]: {}", from, contenido);
                }
                case "interactive" -> {
                    var inter = msg.get("interactive");
                    var sub   = inter.get("type").asText();
                    if ("button_reply".equals(sub)) {
                        var btn    = inter.get("button_reply");
                        var id     = btn.get("id").asText();
                        var title  = btn.get("title").asText();
                        contenido = title;
                        tipo = "button_" + id;
                        log.info("WA botón [{}]: {} ({})", from, title, id);
                    } else {
                        log.info("WA interactive ignorado [{}]: {}", from, sub);
                        return;
                    }
                }
                case "image", "video", "document", "audio", "sticker" -> {
                    var media = msg.get(type);
                    var caption = media.has("caption") ? media.get("caption").asText("") : "";
                    contenido = caption.isBlank() ? "[" + type + "]" : caption;
                    tipo = type;
                    mediaId = media.has("id") ? media.get("id").asText() : "";
                    mimeType = media.has("mime_type") ? media.get("mime_type").asText() : "";
                    log.info("WA {} [{}]: {}", type, from, contenido);
                }
                case "location" -> {
                    var loc = msg.get("location");
                    var name = loc.has("name") ? loc.get("name").asText("") : "";
                    contenido = name.isBlank() ? "📍 Ubicación" : "📍 " + name;
                    tipo = "location";
                    log.info("WA ubicación [{}]", from);
                }
                case "reaction" -> {
                    log.info("WA reacción ignorada [{}]", from);
                    return;
                }
                default -> {
                    log.info("WA tipo ignorado [{}]: {}", from, type);
                    return;
                }
            }

            var cliente = clienteRepo.findByWhatsapp(from).orElse(null);
            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(from)
                    .cliente(cliente)
                    .contenido(contenido)
                    .tipo(tipo)
                    .direccion("ENTRADA")
                    .waMessageId(waId)
                    .mediaId(mediaId.isBlank() ? null : mediaId)
                    .mimeType(mimeType.isBlank() ? null : mimeType)
                    .build());

            if (tipo.startsWith("button_")) {
                procesarBoton(from, tipo, contenido, cliente);
            }

            if (tipo.equals("text")) {
                procesarTextoEntrante(from, contenido);
            }

            if (cliente == null && !conversacionesEnvio.containsKey(from)) {
                var ultima = ultimaInteraccion.get(from);
                if (ultima == null || ChronoUnit.HOURS.between(ultima, Instant.now()) >= 12) {
                    enviarBotones(from, """
                        ¡Hola! 👗 ¿En qué puedo ayudarte?""",
                        List.of(
                            Map.of("id", "envio",   "title", "📦 Quiero mi envío"),
                            Map.of("id", "asesora",  "title", "💬 Hablar con asesor")
                        ));
                }
            }
            ultimaInteraccion.put(from, Instant.now());

        } catch (Exception e) {
            log.error("Error procesando webhook WA", e);
        }
    }

    private void procesarBoton(String from, String tipo, String contenido, com.tiendaropa.domain.model.Cliente cliente) {
        switch (tipo) {
            case "button_envio" -> {
                conversacionesEnvio.put(from, new EnvioConversation());
                enviarMensaje(from, """
                    Te voy a solicitar los siguientes datos para tu envío:
                    
                    • Nombre completo
                    • Teléfono
                    • Cédula
                    • Dirección
                    • Ciudad
                    • Barrio
                    
                    Empecemos. ¿Cuál es tu nombre completo? 📝""");
                log.info("Iniciado flujo envío paso a paso para {}", from);
            }
            case "button_asesora" -> {
                enviarMensaje(from, """
                    Te comunicaré con una asesora. Por favor espera, en breve te atenderemos.""");
            }
        }
    }

    private void procesarTextoEntrante(String from, String contenido) {
        var conv = conversacionesEnvio.get(from);
        if (conv == null) return;

        switch (conv.step) {
            case 0 -> {
                conv.nombre = contenido;
                conv.step = 1;
                enviarMensaje(from, "Gracias. ¿Cuál es tu número de teléfono? 📞");
            }
            case 1 -> {
                conv.telefono = contenido;
                conv.step = 2;
                enviarMensaje(from, "Perfecto. ¿Cuál es tu número de cédula? 🪪");
            }
            case 2 -> {
                conv.cedula = contenido;
                conv.step = 3;
                enviarMensaje(from, "¿Cuál es tu dirección? 📍");
            }
            case 3 -> {
                conv.direccion = contenido;
                conv.step = 4;
                enviarMensaje(from, "¿En qué ciudad te encuentras? 🏙️");
            }
            case 4 -> {
                conv.ciudad = contenido;
                conv.step = 5;
                enviarMensaje(from, "¿Cuál es tu barrio? 🏘️");
            }
            case 5 -> {
                conv.barrio = contenido;
                conversacionesEnvio.remove(from);
                envioService.crearConDatos(from, conv.nombre, conv.telefono, conv.cedula,
                    conv.direccion, conv.ciudad, conv.barrio);
                enviarMensaje(from, """
                    ✅ ¡Gracias! Hemos recibido tus datos de envío.
                    En breve te contactaremos para coordinar la entrega.""");
            }
        }
        log.info("Flujo envío [{}] paso {}: {}", from, conv.step - 1, contenido);
    }

    @Override
    public void enviarMensaje(String destinatario, String texto) {
        var body = Map.of(
            "messaging_product", "whatsapp",
            "to", destinatario,
            "type", "text",
            "text", Map.of("body", texto)
        );

        try {
            var r = whatsappWebClient.post()
                .uri("/{phoneId}/messages", phoneNumberId)
                .header("Authorization", "Bearer " + accessToken)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(destinatario)
                    .contenido(texto)
                    .tipo("text")
                    .direccion("SALIDA")
                    .waMessageId(r.get("messages").get(0).get("id").asText())
                    .build());
        } catch (Exception e) {
            log.error("Error enviando WA a {}", destinatario, e);
            throw new RuntimeException("Error enviando mensaje WhatsApp: " + e.getMessage(), e);
        }
    }

    @Override
    public void enviarBotones(String destinatario, String texto, List<Map<String, String>> botones) {
        var buttons = botones.stream()
            .map(b -> Map.of(
                "type", "reply",
                "reply", Map.of("id", b.get("id"), "title", b.get("title"))
            ))
            .toList();

        var body = Map.of(
            "messaging_product", "whatsapp",
            "to", destinatario,
            "type", "interactive",
            "interactive", Map.of(
                "type", "button",
                "body", Map.of("text", texto),
                "action", Map.of("buttons", buttons)
            )
        );

        try {
            var r = whatsappWebClient.post()
                .uri("/{phoneId}/messages", phoneNumberId)
                .header("Authorization", "Bearer " + accessToken)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

            var titulos = botones.stream().map(b -> b.get("title")).reduce((a, b1) -> a + " | " + b1).orElse("");
            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(destinatario)
                    .contenido(texto + "\n[" + titulos + "]")
                    .tipo("interactive")
                    .direccion("SALIDA")
                    .waMessageId(r.get("messages").get(0).get("id").asText())
                    .build());
        } catch (Exception e) {
            log.error("Error enviando botones WA a {}", destinatario, e);
            throw new RuntimeException("Error enviando botones WhatsApp: " + e.getMessage(), e);
        }
    }

    @Override
    public void enviarNotificacionEnvio(String destinatario, String nombre, String guia) {
        var body = Map.of(
            "messaging_product", "whatsapp",
            "to", destinatario,
            "type", "template",
            "template", Map.of(
                "name", "notificacion_envio",
                "language", Map.of("code", "es"),
                "components", new Object[]{
                    Map.of(
                        "type", "body",
                        "parameters", new Object[]{
                            Map.of("type", "text", "text", nombre),
                            Map.of("type", "text", "text", guia)
                        }
                    )
                }
            )
        );

        whatsappWebClient.post()
            .uri("/{phoneId}/messages", phoneNumberId)
            .header("Authorization", "Bearer " + accessToken)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .subscribe(
                r -> log.info("Plantilla envío enviada a {}", destinatario),
                e -> log.error("Error enviando plantilla", e)
            );
    }

    @Override
    public void enviarConfirmacionApartado(String destinatario, String nombre,
                                           String prenda, String precio) {
        enviarMensaje(destinatario, """
            ✅ ¡Listo %s! Tu prenda "%s" está apartada por $%s.

            Para confirmar envíanos el comprobante de pago a este mismo chat.

            Datos de transferencia:
            • Nequi: 300-xxx-xxxx
            • Bancolombia: 123-456789-12

            ¡Gracias por tu compra! 🛍️""".formatted(nombre, prenda, precio));
    }

    @Override
    public void actualizarNombreCliente(String whatsappFrom, String nombre) {
        var cliente = clienteRepo.findByWhatsapp(whatsappFrom).orElseGet(() ->
                clienteRepo.save(Cliente.builder().whatsapp(whatsappFrom).build()));
        cliente.setNombre(nombre);
        clienteRepo.save(cliente);
    }
}
