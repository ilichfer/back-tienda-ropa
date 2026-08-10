package com.tiendaropa.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.WaMensaje;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.SolicitudEnvioRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.CuentaService;
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
    private final CuentaService cuentaService;

    private static final String FLUJO_ENVIO  = "ENVIO";
    private static final String FLUJO_PEDIDO = "PEDIDO";

    private static final int PEDIDO_CONFIRMAR_FOTO = 0;
    private static final int PEDIDO_NOMBRE         = 1;
    private static final int PEDIDO_VALOR          = 2;
    private static final int PEDIDO_VALOR_TEXTO    = 3;
    private static final int PEDIDO_ENVIO          = 4;

    private static class ConversacionCliente {
        String flujo;
        int paso;
        String nombre;
        String telefono;
        String cedula;
        String direccion;
        String ciudad;
        String barrio;
        String concepto;
        String mediaId;
        String mediaPath;
        String mimeType;
    }

    private final ConcurrentHashMap<String, ConversacionCliente> conversaciones = new ConcurrentHashMap<>();
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
                    } else if ("list_reply".equals(sub)) {
                        var lr     = inter.get("list_reply");
                        var id     = lr.get("id").asText();
                        var title  = lr.get("title").asText();
                        contenido = title;
                        tipo = "list_" + id;
                        log.info("WA lista [{}]: {} ({})", from, title, id);
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

            if (tipo.startsWith("button_") || tipo.startsWith("list_")) {
                procesarBoton(from, tipo, contenido, cliente);
            } else if (tipo.equals("text")) {
                procesarTextoEntrante(from, contenido);
            } else if (tipo.equals("image")) {
                if (!conversaciones.containsKey(from)) {
                    iniciarFlujoPedidoFoto(from, mediaId, mimeType);
                }
            }

            if (tipo.equals("text")) {
                var ultima = ultimaInteraccion.get(from);
                var convVieja = conversaciones.containsKey(from)
                        && ultima != null
                        && ChronoUnit.HOURS.between(ultima, Instant.now()) >= 2;
                if (convVieja) conversaciones.remove(from);

                if (!conversaciones.containsKey(from)
                        && (ultima == null || ChronoUnit.HOURS.between(ultima, Instant.now()) >= 12)) {
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

    private void procesarBoton(String from, String tipo, String contenido, Cliente cliente) {
        if (tipo.startsWith("list_")) {
            procesarLista(from, tipo);
            return;
        }

        switch (tipo) {
            case "button_envio" -> iniciarFlujoEnvio(from);
            case "button_asesora" -> {
                conversaciones.remove(from);
                enviarMensaje(from, """
                    Te comunicaré con una asesora. Por favor espera, en breve te atenderemos.""");
            }
            case "si_foto" -> {
                var conv = conversaciones.get(from);
                if (conv != null && FLUJO_PEDIDO.equals(conv.flujo) && conv.paso == PEDIDO_CONFIRMAR_FOTO) {
                    conv.paso = PEDIDO_VALOR;
                    enviarOpcionesValor(from);
                }
            }
            case "no_foto" -> {
                conversaciones.remove(from);
                enviarMensaje(from, "Entendido 🙂 ¿En qué más te puedo ayudar?");
            }
            case "button_apartar_solo" -> {
                conversaciones.remove(from);
                enviarMensaje(from, "✅ Listo, tu prenda quedó apartada. Cuando quieras pagar o enviar, avísanos. 💜");
            }
            default -> log.info("Botón sin manejo [{}]: {}", from, tipo);
        }
    }

    private void procesarLista(String from, String tipo) {
        var conv = conversaciones.get(from);
        if (conv == null || !FLUJO_PEDIDO.equals(conv.flujo) || conv.paso != PEDIDO_VALOR) return;

        if (tipo.equals("list_valor_nose")) {
            registrarCargoDesdeBot(from, conv, null);
        } else if (tipo.equals("list_valor_otro")) {
            conv.paso = PEDIDO_VALOR_TEXTO;
            enviarMensaje(from, "¿Cuánto cuesta la prenda? Escribe el valor solo en números, por ejemplo: 45000");
        } else if (tipo.startsWith("list_valor_")) {
            try {
                long valor = Long.parseLong(tipo.substring("list_valor_".length()));
                registrarCargoDesdeBot(from, conv, valor);
            } catch (NumberFormatException e) {
                log.warn("Valor de lista no numérico [{}]: {}", from, tipo);
            }
        }
    }

    private void procesarTextoEntrante(String from, String contenido) {
        var conv = conversaciones.get(from);

        if (conv == null) {
            if (esIntencionPedido(contenido)) {
                iniciarFlujoPedidoTexto(from);
            }
            return;
        }

        if (FLUJO_ENVIO.equals(conv.flujo)) {
            procesarTextoEnvio(from, conv, contenido);
            return;
        }

        if (FLUJO_PEDIDO.equals(conv.flujo)) {
            if (conv.paso == PEDIDO_NOMBRE) {
                conv.concepto = contenido.trim();
                conv.paso = PEDIDO_VALOR;
                enviarOpcionesValor(from);
            } else if (conv.paso == PEDIDO_VALOR_TEXTO) {
                var valor = extraerNumero(contenido);
                if (valor == null) {
                    enviarMensaje(from, "No entendí el valor 🤔 Escribe solo números, por ejemplo: 45000");
                } else {
                    registrarCargoDesdeBot(from, conv, valor);
                }
            }
        }
    }

    private void procesarTextoEnvio(String from, ConversacionCliente conv, String contenido) {
        switch (conv.paso) {
            case 0 -> {
                conv.nombre = contenido;
                conv.paso = 1;
                enviarMensaje(from, "Gracias. ¿Cuál es tu número de teléfono? 📞");
            }
            case 1 -> {
                conv.telefono = contenido;
                conv.paso = 2;
                enviarMensaje(from, "Perfecto. ¿Cuál es tu número de cédula? 🪪");
            }
            case 2 -> {
                conv.cedula = contenido;
                conv.paso = 3;
                enviarMensaje(from, "¿Cuál es tu dirección? 📍");
            }
            case 3 -> {
                conv.direccion = contenido;
                conv.paso = 4;
                enviarMensaje(from, "¿En qué ciudad te encuentras? 🏙️");
            }
            case 4 -> {
                conv.ciudad = contenido;
                conv.paso = 5;
                enviarMensaje(from, "¿Cuál es tu barrio? 🏘️");
            }
            case 5 -> {
                conv.barrio = contenido;
                conversaciones.remove(from);
                envioService.crearConDatos(from, conv.nombre, conv.telefono, conv.cedula,
                    conv.direccion, conv.ciudad, conv.barrio);
                enviarMensaje(from, """
                    ✅ ¡Gracias! Hemos recibido tus datos de envío.
                    En breve te contactaremos para coordinar la entrega.""");
            }
        }
        log.info("Flujo envío [{}] paso {}: {}", from, conv.paso - 1, contenido);
    }

    private boolean esIntencionPedido(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase();
        return t.contains("pedir") || t.contains("pedí") || t.contains("pedi")
                || t.contains("apartar") || t.contains("aparta") || t.contains("apart")
                || t.contains("guárdame") || t.contains("guardame") || t.contains("guárdamelo")
                || t.contains("reservar") || t.contains("reserva") || t.contains("pedido");
    }

    private void iniciarFlujoPedidoFoto(String from, String mediaId, String mimeType) {
        var conv = new ConversacionCliente();
        conv.flujo = FLUJO_PEDIDO;
        conv.paso = PEDIDO_CONFIRMAR_FOTO;
        conv.mediaId = mediaId;
        conv.mimeType = mimeType;
        conversaciones.put(from, conv);
        enviarBotones(from, """
            📸 Recibí tu foto. ¿Quieres apartar esta prenda?""",
            List.of(
                Map.of("id", "si_foto", "title", "✅ Sí, la quiero"),
                Map.of("id", "no_foto", "title", "❌ No")
            ));
    }

    private void iniciarFlujoPedidoTexto(String from) {
        var conv = new ConversacionCliente();
        conv.flujo = FLUJO_PEDIDO;
        conv.paso = PEDIDO_NOMBRE;
        conversaciones.put(from, conv);
        enviarMensaje(from, """
            Claro 😊 ¿Qué prenda quieres apartar?
            Escríbela así, por ejemplo: "jean talla 32" o "blusa azul".""");
    }

    private void iniciarFlujoEnvio(String from) {
        var conv = new ConversacionCliente();
        conv.flujo = FLUJO_ENVIO;
        conv.paso = 0;
        conversaciones.put(from, conv);
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

    private void registrarCargoDesdeBot(String from, ConversacionCliente conv, Long valor) {
        var resp = cuentaService.registrarCargo(from, conv.concepto, valor,
                conv.mediaId, conv.mediaPath, conv.mimeType);
        var concepto = resp.concepto() == null || resp.concepto().isBlank()
                ? "la prenda" : "\"" + resp.concepto() + "\"";
        if (valor == null) {
            enviarMensaje(from, "✅ Listo, apartamos %s sin precio (queda por definir). Cuando tengamos el valor te avisamos."
                    .formatted(concepto));
        } else {
            enviarMensaje(from, "✅ ¡Listo! Apartamos %s por $%d.".formatted(concepto, valor));
        }
        conv.paso = PEDIDO_ENVIO;
        enviarBotones(from, "¿Quieres que te lo enviemos? 📦",
            List.of(
                Map.of("id", "envio",        "title", "📦 Sí, quiero mi envío"),
                Map.of("id", "apartar_solo", "title", "✅ No, solo apartarlo"),
                Map.of("id", "asesora",      "title", "💬 Hablar con asesor")
            ));
    }

    private void enviarOpcionesValor(String from) {
        enviarLista(from,
            "¿Cuánto cuesta la prenda? Selecciona una opción:",
            "Seleccionar valor",
            "Valor de la prenda",
            List.of(
                Map.of("id", "list_valor_15000", "title", "$15.000"),
                Map.of("id", "list_valor_20000", "title", "$20.000"),
                Map.of("id", "list_valor_25000", "title", "$25.000"),
                Map.of("id", "list_valor_30000", "title", "$30.000"),
                Map.of("id", "list_valor_35000", "title", "$35.000"),
                Map.of("id", "list_valor_40000", "title", "$40.000"),
                Map.of("id", "list_valor_50000", "title", "$50.000"),
                Map.of("id", "list_valor_60000", "title", "$60.000"),
                Map.of("id", "list_valor_otro", "title", "💰 Otro valor"),
                Map.of("id", "list_valor_nose", "title", "🙈 No sé el valor")
            ));
    }

    private Long extraerNumero(String s) {
        var sb = new StringBuilder();
        for (char c : s.toCharArray()) if (Character.isDigit(c)) sb.append(c);
        if (sb.length() == 0) return null;
        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            return null;
        }
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
    public void enviarLista(String destinatario, String texto, String botonAccion, String tituloSeccion,
                            List<Map<String, String>> opciones) {
        var rows = opciones.stream()
            .map(o -> Map.of("id", o.get("id"), "title", o.get("title")))
            .toList();

        var body = Map.of(
            "messaging_product", "whatsapp",
            "to", destinatario,
            "type", "interactive",
            "interactive", Map.of(
                "type", "list",
                "body", Map.of("text", texto),
                "action", Map.of(
                    "button", botonAccion,
                    "sections", new Object[]{
                        Map.of("title", tituloSeccion, "rows", rows)
                    }
                )
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

            var titulos = opciones.stream().map(o -> o.get("title")).reduce((a, b1) -> a + " | " + b1).orElse("");
            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(destinatario)
                    .contenido(texto + "\n📋 [" + titulos + "]")
                    .tipo("list")
                    .direccion("SALIDA")
                    .waMessageId(r.get("messages").get(0).get("id").asText())
                    .build());
        } catch (Exception e) {
            log.error("Error enviando lista WA a {}", destinatario, e);
            throw new RuntimeException("Error enviando lista WhatsApp: " + e.getMessage(), e);
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
        var mensajes = mensajeRepo.findByWhatsappFromConCliente(whatsappFrom);
        for (var m : mensajes) {
            if (m.getCliente() == null) {
                m.setCliente(cliente);
            }
        }
        mensajeRepo.saveAll(mensajes);
    }
}
