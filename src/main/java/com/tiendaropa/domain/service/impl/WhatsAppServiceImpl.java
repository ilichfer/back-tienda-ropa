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
import com.tiendaropa.domain.service.ia.AgenteIA;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppServiceImpl implements WhatsAppService {

    @Value("${whatsapp.phone-number-id}")
    private String phoneNumberId;

    @Value("${whatsapp.access-token}")
    private String accessToken;

    @Value("${whatsapp.media-dir:./media}")
    private String mediaDir;

    private final WebClient whatsappWebClient;

    private final WaMensajeRepository mensajeRepo;
    private final ClienteRepository   clienteRepo;
    private final EnvioService envioService;
    private final SolicitudEnvioRepository solicitudRepo;
    private final CuentaService cuentaService;
    private final ObjectProvider<AgenteIA> agenteIAProvider;

    @jakarta.annotation.PostConstruct
    public void init() {
        var agente = agenteIAProvider.getIfAvailable();
        log.info("=========================================");
        log.info("[WA-SERVICE] agenteIA.isPresent={}", agente != null);
        if (agente != null) log.info("[WA-SERVICE] Agente: {}", agente.nombre());
        log.info("=========================================");
    }

    private static final String FLUJO_ENVIO  = "ENVIO";
    private static final String FLUJO_PEDIDO = "PEDIDO";

    private static final int PEDIDO_CONFIRMAR_FOTO = 0;
    private static final int PEDIDO_NOMBRE         = 1;
    private static final int PEDIDO_VALOR_TEXTO    = 2;
    private static final int PEDIDO_ENVIO          = 3;

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
        boolean soportePago;
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
            var primerMensaje = !mensajeRepo.existsByWhatsappFrom(from);
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

            log.info("=== WEBHOOK WA === from={} type={} primerMensaje={}", from, tipo, primerMensaje);

            if (tipo.startsWith("button_")) {
                log.info("[FLUJO] Botón detectado, procesando...");
                procesarBoton(from, tipo, contenido, cliente);
            } else if (tipo.equals("text")) {
                log.info("[FLUJO] Texto entrante, llamando procesarTextoEntrante...");
                var agenteRespondio = procesarTextoEntrante(from, contenido);
                log.info("[FLUJO] procesarTextoEntrante devolvió agenteRespondio={}", agenteRespondio);
                if (agenteRespondio) {
                    log.info("[FLUJO] Agente IA respondió, saltando saludos hardcoded");
                    ultimaInteraccion.put(from, Instant.now());
                    return;
                }
                log.info("[FLUJO] Sin agente IA, continúa con saludos hardcoded");
            } else if (tipo.equals("image")) {
                log.info("[FLUJO] Imagen recibida, procesando con agente IA...");
                procesarImagenConIA(from, mediaId, mimeType);
            }

            if (tipo.equals("text")) {
                var ultima = ultimaInteraccion.get(from);
                var convVieja = conversaciones.containsKey(from)
                        && ultima != null
                        && ChronoUnit.HOURS.between(ultima, Instant.now()) >= 2;
                if (convVieja) conversaciones.remove(from);

                if (primerMensaje) {
                    enviarMensaje(from, """
                        ¡Hola! 👋 Bienvenido/a al Patio de Ropa Jireh 🛍️💜

                        Para poder atenderte mejor, por favor me regalas tu usuario de TikTok y tu nombre completo 😊""");
                } else if (!conversaciones.containsKey(from)
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
        switch (tipo) {
            case "button_envio" -> iniciarFlujoEnvio(from);
            case "button_asesora" -> {
                conversaciones.remove(from);
                enviarMensaje(from, """
                    Te comunicaré con una asesora. Por favor espera, en breve te atenderemos.""");
            }
            case "button_si_foto" -> {
                var conv = conversaciones.get(from);
                if (conv != null && FLUJO_PEDIDO.equals(conv.flujo) && conv.paso == PEDIDO_CONFIRMAR_FOTO) {
                    preguntarValor(from, conv);
                }
            }
            case "button_soporte_pago" -> {
                var conv = conversaciones.get(from);
                if (conv != null && FLUJO_PEDIDO.equals(conv.flujo) && conv.paso == PEDIDO_CONFIRMAR_FOTO) {
                    conv.soportePago = true;
                    preguntarValorPago(from, conv);
                }
            }
            case "button_no_foto" -> {
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

    private void preguntarValor(String from, ConversacionCliente conv) {
        conv.paso = PEDIDO_VALOR_TEXTO;
        enviarMensaje(from, """
            ¿Cuánto cuesta la prenda? Escribe el valor en números, por ejemplo: 45000.
            Si no lo recuerdas, escribe "no sé".""");
    }

    private void preguntarValorPago(String from, ConversacionCliente conv) {
        conv.paso = PEDIDO_VALOR_TEXTO;
        enviarMensaje(from, """
            💳 ¿Cuánto pagaste? Escribe el valor en números, por ejemplo: 45000.""");
    }

    private boolean procesarTextoEntrante(String from, String contenido) {
        var conv = conversaciones.get(from);
        log.info("[TEXTO] from={} convActiva={} contenido='{}'", from, conv != null, contenido);

        if (conv == null) {
            if (esIntencionPedido(contenido)) {
                log.info("[TEXTO] Intención de pedido detectada, iniciando flujo PEDIDO");
                iniciarFlujoPedidoTexto(from);
                return false;
            }
            log.info("[TEXTO] Sin conversación activa ni intención de pedido, intentando agente IA...");
            return responderConAgenteIA(from, contenido);
        }

        if (FLUJO_ENVIO.equals(conv.flujo)) {
            procesarTextoEnvio(from, conv, contenido);
            return false;
        }

        if (FLUJO_PEDIDO.equals(conv.flujo)) {
            if (conv.paso == PEDIDO_NOMBRE) {
                conv.concepto = contenido.trim();
                preguntarValor(from, conv);
            } else if (conv.paso == PEDIDO_VALOR_TEXTO) {
                if (conv.soportePago) {
                    var valor = extraerNumero(contenido);
                    if (valor == null) {
                        enviarMensaje(from, "No entendí el valor 🤔 Escribe solo números, por ejemplo: 45000");
                    } else {
                        registrarAbonoDesdeBot(from, conv, valor);
                    }
                } else if (esNoSe(contenido)) {
                    registrarCargoDesdeBot(from, conv, null);
                    return false;
                } else {
                    var valor = extraerNumero(contenido);
                    if (valor == null) {
                        enviarMensaje(from, "No entendí el valor 🤔 Escribe solo números, por ejemplo: 45000");
                    } else {
                        registrarCargoDesdeBot(from, conv, valor);
                    }
                }
            }
        }
        return false;
    }

    private boolean responderConAgenteIA(String from, String contenido) {
        var agente = agenteIAProvider.getIfAvailable();
        log.info("[IA] Verificando agente IA... agenteIA.isPresent={}", agente != null);
        if (agente == null) {
            log.info("[IA] No hay agente IA configurado, retornando false");
            return false;
        }

        try {
            log.info("[IA] Agente IA disponible: {}, construyendo contexto...", agente.nombre());
            var contexto = construirContexto(from);
            log.info("[IA] Contexto construido ({} chars), llamando a Gemini...", contexto.length());
            var respuesta = agente.responder(contenido, contexto);
            log.info("[IA] Gemini devolvió respuesta presente={}", respuesta.isPresent());
            if (respuesta.isPresent()) {
                var texto = respuesta.get();
                log.info("[IA] Enviando respuesta IA: '{}'", texto.substring(0, Math.min(100, texto.length())));

                if (texto.contains("[SOLICITAR_ENVIO]")) {
                    log.info("[IA] Detectada solicitud de envío, iniciando flujo ENVIO");
                    var mensajeLimpio = texto.replace("[SOLICITAR_ENVIO]", "").trim();
                    enviarMensaje(from, mensajeLimpio);
                    iniciarFlujoEnvio(from);
                    return true;
                }

                enviarMensaje(from, texto);
                return true;
            } else {
                log.warn("[IA] Gemini no devolvió respuesta, enviando botones de fallback");
                enviarBotones(from, "¿En qué puedo ayudarte?",
                    List.of(
                        Map.of("id", "envio",   "title", "📦 Quiero mi envío"),
                        Map.of("id", "asesora",  "title", "💬 Hablar con asesor")
                    ));
                return true;
            }
        } catch (Exception e) {
            log.error("[IA] Error en agente IA para {}", from, e);
            enviarMensaje(from, "Disculpa, no pude procesar tu mensaje. Intenta de nuevo o habla con un asesor. 💬");
            return true;
        }
    }

    private void procesarImagenConIA(String from, String mediaId, String mimeType) {
        var agente = agenteIAProvider.getIfAvailable();
        if (agente == null) {
            log.info("[IA] No hay agente IA, procesando imagen con flujo legacy");
            var conv = conversaciones.get(from);
            if (conv == null || FLUJO_PEDIDO.equals(conv.flujo)) {
                iniciarFlujoPedidoFoto(from, mediaId, mimeType);
            }
            return;
        }

        try {
            var bytes = descargarImagenBytes(mediaId);
            if (bytes == null || bytes.length == 0) {
                log.warn("[IA] No se pudo descargar la imagen {}", mediaId);
                enviarMensaje(from, "No pude recibir la imagen. Intenta enviarla de nuevo. 📷");
                return;
            }

            log.info("[IA] Imagen descargada ({} bytes), enviando a Gemini para análisis...", bytes.length);
            var contexto = construirContexto(from);
            var analisis = agente.analizarImagen(bytes, mimeType, contexto);

            if (analisis.isEmpty()) {
                log.warn("[IA] Gemini no pudo analizar la imagen, enviando fallback");
                enviarMensaje(from, "No pude identificar la imagen. ¿Es una foto de la prenda o un comprobante de pago? 💬");
                return;
            }

            var resultado = analisis.get();
            log.info("[IA] Imagen clasificada como: {}", resultado.tipo());

            if (resultado.esPrenda()) {
                var conv = new ConversacionCliente();
                conv.flujo = FLUJO_PEDIDO;
                conv.paso = PEDIDO_CONFIRMAR_FOTO;
                conv.mediaId = mediaId;
                conv.mimeType = mimeType;
                conversaciones.put(from, conv);
                enviarMensaje(from, resultado.respuesta());
            } else if (resultado.esComprobante()) {
                var conv = conversaciones.get(from);
                if (conv != null && FLUJO_PEDIDO.equals(conv.flujo)) {
                    conv.mediaPath = descargarMediaLocal(mediaId);
                    conv.mimeType = mimeType;
                    registrarAbonoDesdeBot(from, conv, null);
                } else {
                    enviarMensaje(from, resultado.respuesta());
                }
            } else {
                enviarMensaje(from, resultado.respuesta());
            }

        } catch (Exception e) {
            log.error("[IA] Error procesando imagen con IA para {}", from, e);
            enviarMensaje(from, "No pude procesar la imagen. Intenta de nuevo o habla con un asesor. 💬");
        }
    }

    private byte[] descargarImagenBytes(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) return null;
        try {
            var meta = whatsappWebClient.get()
                .uri("/{mediaId}", mediaId)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
            var urlStr = meta.has("url") ? meta.get("url").asText() : null;
            if (urlStr == null) return null;

            return whatsappWebClient.get()
                .uri(urlStr)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
        } catch (Exception e) {
            log.warn("No se pudo descargar bytes de media {}: {}", mediaId, e.getMessage());
            return null;
        }
    }

    private String construirContexto(String from) {
        var sb = new StringBuilder();
        sb.append("Cliente con teléfono: ").append(from).append("\n");

        var cliente = clienteRepo.findByWhatsapp(from).orElse(null);
        if (cliente != null) {
            if (cliente.getNombre() != null) sb.append("Nombre: ").append(cliente.getNombre()).append("\n");
            if (cliente.getCiudad() != null) sb.append("Ciudad: ").append(cliente.getCiudad()).append("\n");
        }

        var ultimosMensajes = mensajeRepo.findByWhatsappFromConCliente(from);
        if (ultimosMensajes != null && !ultimosMensajes.isEmpty()) {
            var recientes = ultimosMensajes.stream()
                    .filter(m -> m.getDireccion() != null && m.getCreatedAt() != null)
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .limit(10)
                    .toList();
            if (!recientes.isEmpty()) {
                sb.append("\nÚltimos mensajes de la conversación:\n");
                for (var m : recientes) {
                    var dir = "ENTRADA".equals(m.getDireccion()) ? "Cliente" : "Tú";
                    sb.append(dir).append(": ").append(m.getContenido()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private boolean esNoSe(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase().trim();
        return t.equals("no se") || t.equals("no sé") || t.equals("nose") || t.equals("no se el valor") || t.equals("no se cuanto vale");
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
            📸 Recibí tu foto. ¿Qué quieres hacer?""",
            List.of(
                Map.of("id", "si_foto",       "title", "✅ Apartar prenda"),
                Map.of("id", "soporte_pago",  "title", "💳 Es pago"),
                Map.of("id", "no_foto",       "title", "❌ Nada")
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
        if (conv.mediaPath == null && conv.mediaId != null) {
            conv.mediaPath = descargarMediaLocal(conv.mediaId);
        }
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
                Map.of("id", "envio",        "title", "📦 Quiero mi envío"),
                Map.of("id", "apartar_solo", "title", "✅ Solo apartar"),
                Map.of("id", "asesora",      "title", "💬 Hablar con asesor")
            ));
    }

    private void registrarAbonoDesdeBot(String from, ConversacionCliente conv, Long valor) {
        if (conv.mediaPath == null && conv.mediaId != null) {
            conv.mediaPath = descargarMediaLocal(conv.mediaId);
        }
        cuentaService.registrarAbonoDesdeBot(from, valor, conv.mediaId, conv.mediaPath, conv.mimeType);
        conversaciones.remove(from);
        if (valor != null) {
            enviarMensaje(from, "💳 ¡Listo! Registramos tu soporte de pago por $%d. La foto quedó en tu cuenta y la revisaremos para confirmarla. 💜"
                    .formatted(valor));
        } else {
            enviarMensaje(from, "💳 ¡Listo! Registramos tu comprobante de pago. La foto quedó en tu cuenta y la revisaremos para confirmarla. 💜");
        }
    }

    private String descargarMediaLocal(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) return null;
        try {
            var meta = whatsappWebClient.get()
                .uri("/{mediaId}", mediaId)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
            var urlStr = meta.has("url") ? meta.get("url").asText() : null;
            if (urlStr == null) return null;
            var mime = meta.has("mime_type") ? meta.get("mime_type").asText() : "image/jpeg";
            var ext = extensionSegunMime(mime);

            var bytes = whatsappWebClient.get()
                .uri(urlStr)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
            if (bytes == null || bytes.length == 0) return null;

            var fileName = UUID.randomUUID() + "." + ext;
            var targetDir = Paths.get(mediaDir, "cuentas");
            Files.createDirectories(targetDir);
            Files.write(targetDir.resolve(fileName), bytes);
            log.info("Media {} descargada local como {}", mediaId, fileName);
            return "cuentas/" + fileName;
        } catch (Exception e) {
            log.warn("No se pudo descargar media {} local: {}", mediaId, e.getMessage());
            return null;
        }
    }

    private static String extensionSegunMime(String mime) {
        if (mime == null) return "bin";
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            default -> mime.contains("image/") ? "img" : "bin";
        };
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
                "reply", Map.of("id", b.get("id"), "title", truncar(b.get("title"), 20))
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

    private static String truncar(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
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
