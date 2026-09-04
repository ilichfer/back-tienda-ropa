package com.tiendaropa.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.WaMensaje;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.CuentaRepository;
import com.tiendaropa.domain.repository.CuentaMovimientoRepository;
import com.tiendaropa.domain.repository.PedidoRepository;
import com.tiendaropa.domain.repository.SolicitudEnvioRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.CuentaService;
import com.tiendaropa.domain.service.EnvioService;
import com.tiendaropa.domain.service.InconvenienteService;
import com.tiendaropa.domain.service.WhatsAppService;
import com.tiendaropa.domain.service.conversation.ConversacionCliente;
import com.tiendaropa.domain.service.conversation.ConversationStateStore;
import com.tiendaropa.domain.service.conversation.PrendaPendiente;
import com.tiendaropa.domain.service.ia.AgenteIA;
import com.tiendaropa.domain.service.intent.IntentDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    // ID del Flow de "Datos de envío" en WhatsApp Manager. Mientras esté vacío (no configurado),
    // iniciarFlujoEnvio() sigue usando las preguntas de texto de siempre — así este cambio no
    // afecta nada hasta que el Flow esté creado y publicado y se pegue su ID acá.
    @Value("${whatsapp.flow-envio-id:}")
    private String flowEnvioId;

    private final WebClient whatsappWebClient;

    private final WaMensajeRepository mensajeRepo;
    private final ClienteRepository   clienteRepo;
    private final EnvioService envioService;
    private final SolicitudEnvioRepository solicitudRepo;
    private final CuentaService cuentaService;
    private final CuentaRepository cuentaRepo;
    private final CuentaMovimientoRepository cuentaMovimientoRepo;
    private final InconvenienteService inconvenienteService;
    private final PedidoRepository pedidoRepo;
    private final List<AgenteIA> agentesIA;
    private final IntentDetector intentDetector;
    private final ConversationStateStore stateStore;

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("=========================================");
        log.info("[WA-SERVICE] agentes IA activos ({}): {}", agentesIA.size(),
                agentesIA.stream().map(AgenteIA::nombre).toList());
        log.info("=========================================");
    }

    private static final String FLUJO_ENVIO  = "ENVIO";
    private static final String FLUJO_PEDIDO = "PEDIDO";
    private static final String FLUJO_INCONVENIENTE = "INCONVENIENTE";

    private static final int PEDIDO_CONFIRMAR_FOTO = 0;
    private static final int PEDIDO_NOMBRE         = 1;
    private static final int PEDIDO_VALOR_TEXTO    = 2;
    private static final int PEDIDO_ENVIO          = 3;

    private static final int ENVIO_CONFIRMAR = 6;

    private static final int INC_DESCRIPCION = 0;
    private static final int INC_FOTOS       = 1;
    private static final int INC_CONFIRMAR   = 2;


    // Meta reintenta (y reenvía duplicado) un webhook si no le respondemos rápido, y este
    // método puede tardar varios segundos por la descarga de imágenes + análisis con la IA.
    // Con @Async, el controller le responde "recibido" a Meta de inmediato mientras esto
    // corre en segundo plano — así se evitan los reintentos/duplicados en vez de solo
    // tolerarlos (ver el índice único de wa_message_id, que sigue como red de seguridad).
    @Override
    @Async
    public void procesarWebhook(JsonNode payload) {
        try {
            var entry    = payload.get("entry").get(0);
            var change   = entry.get("changes").get(0).get("value");
            var messages = change.get("messages");

            if (messages == null || messages.isEmpty()) {
                // No es un mensaje nuevo — puede ser una confirmación de entrega (statuses:
                // sent/delivered/read/failed) de un mensaje que nosotros mandamos. Antes esto
                // se ignoraba por completo y el panel nunca sabía si un envío realmente llegó.
                var statuses = change.get("statuses");
                if (statuses != null && !statuses.isEmpty()) {
                    statuses.forEach(this::actualizarEstadoEntrega);
                }
                return;
            }

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
                    } else if ("nfm_reply".equals(sub)) {
                        // Respuesta de un formulario nativo (Flow) de WhatsApp, ej. el de datos
                        // de envío. response_json trae, como texto, el JSON con lo que la
                        // persona llenó en el formulario.
                        contenido = inter.get("nfm_reply").get("response_json").asText();
                        tipo = "flow_reply";
                        log.info("WA respuesta de Flow [{}]: {}", from, contenido);
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
                    var address = loc.has("address") ? loc.get("address").asText("") : "";
                    var partes = new java.util.ArrayList<String>();
                    if (!name.isBlank()) partes.add(name);
                    if (!address.isBlank()) partes.add(address);
                    contenido = partes.isEmpty() ? "📍 Ubicación" : "📍 " + String.join(" - ", partes);
                    tipo = "location";
                    log.info("WA ubicación [{}]: {}", from, contenido);
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

            // Si el cliente respondió citando un mensaje/foto desde su teléfono (la acción de
            // WhatsApp Web/app de "responder"), el webhook trae un objeto "context" con el id
            // del mensaje original citado. Se guarda para poder mostrar esa cita en el panel.
            var contextWaMessageId = msg.has("context") && msg.get("context").has("id")
                    ? msg.get("context").get("id").asText()
                    : null;

            try {
                mensajeRepo.save(WaMensaje.builder()
                        .whatsappFrom(from)
                        .cliente(cliente)
                        .contenido(contenido)
                        .tipo(tipo)
                        .direccion("ENTRADA")
                        .waMessageId(waId)
                        .contextWaMessageId(contextWaMessageId)
                        .mediaId(mediaId.isBlank() ? null : mediaId)
                        .mimeType(mimeType.isBlank() ? null : mimeType)
                        .build());
            } catch (DataIntegrityViolationException dup) {
                // Meta reintenta el webhook si no confirmamos a tiempo. El índice único de
                // wa_message_id (ver schema.sql) detecta el reintento acá y evitamos procesar
                // el mensaje dos veces (doble solicitud de envío, doble llamada a la IA, etc.).
                log.info("Webhook WA duplicado ignorado (wa_message_id={} ya estaba registrado)", waId);
                return;
            }

            log.info("=== WEBHOOK WA === from={} type={} primerMensaje={}", from, tipo, primerMensaje);
            stateStore.recuperarSiNecesario(from);

            // Todo lo que sigue queda envuelto en un try/finally: antes cada punto de salida
            // (cada "return" de acá abajo) tenía que acordarse de marcar la interacción y
            // persistir la conversación A MANO antes de retornar, y era fácil que un flujo
            // nuevo lo olvidara — el estado quedaba sin guardar en BD hasta el próximo mensaje
            // que sí pasara por el camino correcto. Con el finally esto pasa siempre, sin
            // depender de que cada rama nueva se acuerde de hacerlo, e incluso si algo de acá
            // adentro lanza una excepción (antes esos casos NO persistían nada).
            try {
                if (cliente != null && Boolean.TRUE.equals(cliente.getBotSilenciado())) {
                    // Interruptor manual del operador: el mensaje queda guardado en el historial
                    // (ya se hizo arriba) pero el bot no procesa ni responde nada más.
                    log.info("[BOT] Bot silenciado manualmente para {}, no se procesa automáticamente", from);
                    return;
                }

                if (tipo.startsWith("button_")) {
                    log.info("[FLUJO] Botón detectado, procesando...");
                    procesarBoton(from, tipo, contenido, cliente);
                } else if (tipo.equals("flow_reply")) {
                    log.info("[FLUJO] Respuesta de Flow detectada, procesando...");
                    procesarRespuestaFlujoEnvio(from, contenido);
                } else if (tipo.equals("text")) {
                    log.info("[FLUJO] Texto entrante, llamando procesarTextoEntrante...");
                    var agenteRespondio = procesarTextoEntrante(from, contenido, primerMensaje);
                    log.info("[FLUJO] procesarTextoEntrante devolvió agenteRespondio={}", agenteRespondio);
                    if (agenteRespondio) {
                        log.info("[FLUJO] Agente IA respondió, saltando saludos hardcoded");
                        return;
                    }
                    log.info("[FLUJO] Sin agente IA, continúa con saludos hardcoded");
                } else if (tipo.equals("image")) {
                    log.info("[FLUJO] Imagen recibida, procesando con agente IA...");
                    procesarImagenConIA(from, mediaId, mimeType, contenido, waId);
                } else if (tipo.equals("audio")) {
                    log.info("[FLUJO] Audio recibido, procesando con agente IA...");
                    procesarAudioConIA(from, mediaId, mimeType);
                } else if (tipo.equals("location")) {
                    procesarUbicacion(from, contenido);
                }

                if (tipo.equals("text")) {
                    var ultima = stateStore.getUltimaInteraccion(from);
                    var convVieja = stateStore.existe(from)
                            && ultima != null
                            && ChronoUnit.HOURS.between(ultima, Instant.now()) >= 2;
                    if (convVieja) {
                        stateStore.remove(from);
                    }

                    if (primerMensaje) {
                        enviarMensaje(from, """
                            ¡Hola! 👋 Bienvenido/a al Patio de Ropa Jireh 🛍️💜

                            Para poder atenderte mejor, por favor me regalas tu usuario de TikTok y tu nombre completo 😊

                            Y no te vayas a perder nuestros lives: únete a nuestro grupo de WhatsApp, ahí avisamos el día y la hora de cada uno 👇
                            %s""".formatted(LINK_GRUPO_WHATSAPP));
                    }
                }
            } finally {
                stateStore.marcarInteraccion(from);
                stateStore.persistir(from);
            }

        } catch (Exception e) {
            log.error("Error procesando webhook WA", e);
        }
    }

    // Un objeto de "statuses" del webhook luce como:
    // { "id": "<wa_message_id>", "status": "delivered", "timestamp": "...",
    //   "errors": [{ "title": "...", "message": "..." }] }  (errors solo si status=failed)
    private void actualizarEstadoEntrega(JsonNode status) {
        try {
            var waMessageId = status.hasNonNull("id") ? status.get("id").asText() : null;
            var estado = status.hasNonNull("status") ? status.get("status").asText() : null;
            if (waMessageId == null || estado == null) return;

            var mensaje = mensajeRepo.findByWaMessageId(waMessageId).orElse(null);
            if (mensaje == null) {
                // Puede pasar si el mensaje se borró de nuestra BD (borrar conversación) pero
                // Meta todavía manda confirmaciones tardías del que ya no existe — no es un error.
                return;
            }

            mensaje.setEstadoEntrega(estado);
            if ("failed".equals(estado) && status.has("errors") && !status.get("errors").isEmpty()) {
                var err = status.get("errors").get(0);
                var titulo = err.hasNonNull("title") ? err.get("title").asText() : "Error desconocido";
                var detalle = err.hasNonNull("message") ? err.get("message").asText() : "";
                mensaje.setErrorEntrega(detalle.isBlank() ? titulo : titulo + ": " + detalle);
            }
            mensajeRepo.save(mensaje);
            log.info("[ESTADO] {} -> {} ({})", waMessageId, estado, mensaje.getWhatsappFrom());
        } catch (Exception e) {
            log.warn("No se pudo procesar status de entrega: {}", e.getMessage());
        }
    }

    private void procesarBoton(String from, String tipo, String contenido, Cliente cliente) {
        switch (tipo) {
            case "button_envio" -> iniciarFlujoEnvio(from);
            case "button_apartar" -> {
                stateStore.remove(from);
                iniciarFlujoPedidoTexto(from);
            }
            case "button_asesora" -> {
                stateStore.remove(from);
                clienteRepo.findByWhatsapp(from).ifPresent(c -> {
                    c.setRequiereAsesor(true);
                    clienteRepo.save(c);
                });
                enviarMensaje(from, """
                    Te comunicaré con una asesora. Por favor espera, en breve te atenderemos.""");
            }
            case "button_si_foto" -> {
                var conv = stateStore.get(from);
                if (conv != null && FLUJO_PEDIDO.equals(conv.flujo) && conv.paso == PEDIDO_CONFIRMAR_FOTO) {
                    preguntarValor(from, conv);
                }
            }
            case "button_soporte_pago" -> {
                var conv = stateStore.get(from);
                if (conv != null && FLUJO_PEDIDO.equals(conv.flujo) && conv.paso == PEDIDO_CONFIRMAR_FOTO) {
                    conv.soportePago = true;
                    preguntarValorPago(from, conv);
                }
            }
            case "button_no_foto" -> {
                var conv = stateStore.get(from);
                if (conv == null || !avanzarSiguientePrendaPendiente(from, conv)) {
                    stateStore.remove(from);
                    enviarMensaje(from, "Entendido 🙂 ¿En qué más te puedo ayudar?");
                }
            }
            case "button_apartar_solo" -> {
                // Este botón viene del mensaje "¿Quieres que te lo enviemos?" (paso
                // PEDIDO_ENVIO). Si para cuando llega el tap ya no hay ese flujo activo en ese
                // paso —por ejemplo, el cliente tocó un botón viejo de una conversación ya
                // abandonada mientras ahora está en medio de un reporte de inconveniente—, no
                // hay nada que confirmar: se ignora, igual que ya hacen button_si_foto,
                // button_soporte_pago, etc. Antes esto respondía "tu prenda quedó apartada"
                // sin importar el contexto, lo que confundía al cliente.
                var conv = stateStore.get(from);
                if (conv != null && FLUJO_PEDIDO.equals(conv.flujo) && conv.paso == PEDIDO_ENVIO) {
                    stateStore.remove(from);
                    enviarMensaje(from, "✅ Listo, tu prenda quedó apartada. Cuando quieras pagar o enviar, avísanos. 💜");
                }
            }
            case "button_inconveniente_mas_fotos" -> {
                var conv = stateStore.get(from);
                if (conv != null && FLUJO_INCONVENIENTE.equals(conv.flujo)) {
                    conv.paso = INC_FOTOS;
                    enviarMensaje(from, "📸 Envíame la siguiente foto del problema.");
                }
            }
            case "button_inconveniente_listo" -> {
                var conv = stateStore.get(from);
                if (conv != null && FLUJO_INCONVENIENTE.equals(conv.flujo)) {
                    finalizarInconveniente(from, conv);
                }
            }
            case "button_envio_confirmar" -> {
                var conv = stateStore.get(from);
                if (conv != null && FLUJO_ENVIO.equals(conv.flujo) && conv.paso == ENVIO_CONFIRMAR) {
                    // Antes, si crearConDatos fallaba (ej. un dato que no pasa una validación de
                    // la BD), la excepción se perdía en el catch general del webhook: al cliente
                    // no le llegaba ningún mensaje, quedaba viendo los botones sin que pasara
                    // nada, y en BD no quedaba registrada la solicitud — sin ningún rastro de
                    // qué salió mal. Ahora se registra el motivo real en el log y se le avisa
                    // al cliente para que pueda reintentar en vez de quedar sin respuesta.
                    try {
                        envioService.crearConDatos(from, conv.nombre, conv.telefono, conv.cedula,
                            conv.direccion, conv.ciudad, conv.barrio);
                        stateStore.remove(from);
                        enviarMensaje(from, """
                            ✅ ¡Gracias! Hemos recibido tus datos de envío.
                            En breve te contactaremos para coordinar la entrega.""");
                        log.info("Flujo envío [{}] confirmado y creado", from);
                    } catch (Exception e) {
                        log.error("[ENVIO] No se pudo guardar la solicitud de envío de {}: {}", from, e.getMessage(), e);
                        enviarMensaje(from, """
                            😕 Tuvimos un problema guardando tus datos. ¿Puedes tocar "Confirmar" de nuevo? Si el problema sigue, un asesor te ayudará. 💜""");
                    }
                } else {
                    // El botón llegó pero el estado en memoria/BD no coincide con lo esperado
                    // (por ejemplo, si el flujo se venció o se perdió por algún motivo) — antes
                    // esto no hacía absolutamente nada y el cliente se quedaba sin saber por qué
                    // su confirmación no sirvió de nada. Ahora se le avisa y se reinicia el flujo
                    // para que pueda volver a dar sus datos sin quedar bloqueado.
                    log.warn("[ENVIO] Botón 'Confirmar' de {} llegó sin el estado esperado (conv={}, flujo={}, paso={}) — no se guardó nada, se reinicia el flujo",
                        from, conv != null, conv == null ? null : conv.flujo, conv == null ? null : conv.paso);
                    enviarMensaje(from, """
                        Parece que pasó un momento desde que empezamos con tus datos de envío. Vamos a tomarlos de nuevo para asegurarnos de que todo quede bien. 🙏""");
                    iniciarFlujoEnvio(from);
                }
            }
            case "button_envio_reiniciar" -> {
                var conv = stateStore.get(from);
                if (conv != null && FLUJO_ENVIO.equals(conv.flujo)) {
                    log.info("Flujo envío [{}] reiniciado por el cliente para corregir datos", from);
                    iniciarFlujoEnvio(from);
                }
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

    /**
     * Si quedan prendas en cola (fotos que llegaron mientras se procesaba otra, o prendas
     * sin foto agregadas a mitad del flujo), toma la siguiente y continúa preguntando por
     * ella (botones si tiene foto, directo el precio si no la tiene). Devuelve true si había
     * una siguiente prenda y se avanzó a ella, o false si la cola estaba vacía y el llamador
     * debe seguir con su flujo normal (preguntar por envío, cerrar la conversación, etc.).
     */
    private boolean avanzarSiguientePrendaPendiente(String from, ConversacionCliente conv) {
        if (conv.prendasPendientes == null || conv.prendasPendientes.isEmpty()) return false;
        var siguiente = conv.prendasPendientes.remove(0);
        conv.mediaId = siguiente.mediaId;
        conv.mimeType = siguiente.mimeType;
        conv.mediaPath = null;
        conv.concepto = siguiente.descripcionSinFoto;
        conv.soportePago = false;
        if (siguiente.mediaId != null) {
            conv.paso = PEDIDO_CONFIRMAR_FOTO;
            // Se cita la foto de esta prenda (si tenemos su wa_message_id) para que quede
            // claro a cuál de las fotos en cola se refiere, cuando el cliente mandó varias.
            enviarBotones(from, "📸 Sigamos con la siguiente prenda. ¿Qué quieres hacer con ella?",
                List.of(
                    Map.of("id", "si_foto",       "title", "✅ Guardar en baúl"),
                    Map.of("id", "soporte_pago",  "title", "💳 Registrar pago")
                ),
                siguiente.waMessageId);
        } else {
            enviarMensaje(from, "Ahora la siguiente prenda que mencionaste: \"%s\"."
                    .formatted(conv.concepto != null ? conv.concepto : "sin descripción"));
            preguntarValor(from, conv);
        }
        return true;
    }

    private boolean procesarTextoEntrante(String from, String contenido, boolean primerMensaje) {
        var conv = stateStore.get(from);
        log.info("[TEXTO] from={} convActiva={} contenido='{}'", from, conv != null, contenido);

        if (conv != null) {
            var ultima = stateStore.getUltimaInteraccion(from);
            if (ultima == null || ChronoUnit.MINUTES.between(ultima, Instant.now()) >= 30) {
                log.info("[TEXTO] Conversación expirada (>30 min), eliminando y procesando como nueva");
                stateStore.remove(from);
                conv = null;
            }
        }

        // Igual que el saldo: la pregunta de CÓMO/A DÓNDE pagar se responde siempre, sin
        // importar si hay un flujo activo, y se revisa ANTES que esIntencionSaldo (ver el
        // comentario de ese método para el porqué del orden).
        if (intentDetector.esIntencionComoPagar(contenido)) {
            log.info("[TEXTO] Intención de método de pago detectada (flujo activo={}), respondiendo sin interrumpirlo", conv != null ? conv.flujo : "ninguno");
            responderComoPagar(from);
            return true;
        }

        // El agente no conoce los precios reales de las prendas (solo el asesor los tiene a
        // mano), así que cualquier pregunta de precio se escala directo a un humano en vez de
        // dejar que la IA invente o adivine un valor. Excepción: dentro de PEDIDO_VALOR_TEXTO
        // es el CLIENTE quien está reportando el valor de lo que está apartando (para llevar
        // su cuenta), no preguntando el precio de catálogo — ese paso ya maneja su propio
        // "no sé" (ver esNoSe) y no debe interrumpirse con esto.
        var enPasoDeValorReportado = conv != null && FLUJO_PEDIDO.equals(conv.flujo) && conv.paso == PEDIDO_VALOR_TEXTO;
        if (!enPasoDeValorReportado && intentDetector.esIntencionPrecio(contenido)) {
            log.info("[TEXTO] Intención de precio detectada (flujo activo={}), escalando a asesor", conv != null ? conv.flujo : "ninguno");
            responderEscalarPrecio(from);
            return true;
        }

        // La pregunta por el saldo se responde SIEMPRE, tenga o no un flujo guiado activo
        // (envío/pedido/inconveniente en curso). Antes solo se detectaba cuando no había
        // conversación activa, y por eso un cliente a mitad de otro flujo que preguntaba
        // "¿cuánto debo?" no recibía ninguna respuesta (el mensaje se perdía en la lógica
        // específica de ese flujo). No se borra el flujo activo: el cliente puede seguir
        // completándolo después de ver su saldo.
        if (intentDetector.esIntencionSaldo(contenido)) {
            log.info("[TEXTO] Intención de saldo detectada (flujo activo={}), respondiendo sin interrumpirlo", conv != null ? conv.flujo : "ninguno");
            responderSaldo(from);
            return true;
        }

        // Igual que saldo/precio: se responde siempre, sin importar si hay un flujo guiado
        // activo, porque no interfiere con ningún paso de esos flujos (solo informa).
        if (intentDetector.esIntencionHorarioLive(contenido)) {
            log.info("[TEXTO] Intención de horario de live detectada (flujo activo={}), respondiendo sin interrumpirlo", conv != null ? conv.flujo : "ninguno");
            responderHorarioLive(from);
            return true;
        }

        // Pregunta general de horario de despacho (no "quiero MI envío", eso es
        // esIntencionEnvio y sigue su propio flujo guiado más abajo). A diferencia del precio,
        // acá sí hay un dato fijo y correcto, así que se responde directo en vez de escalar a
        // asesor — y sobre todo, en vez de dejar que la IA invente días/ciudad como pasó antes.
        if (intentDetector.esIntencionInfoEnvios(contenido)) {
            log.info("[TEXTO] Intención de horario de envíos detectada (flujo activo={}), respondiendo sin interrumpirlo", conv != null ? conv.flujo : "ninguno");
            responderHorarioEnvios(from);
            return true;
        }

        if (conv == null) {
            // En el primerísimo mensaje de un número nuevo todavía no lo hemos saludado ni
            // sabemos nada de él: no tiene sentido lanzarlo directo a un flujo rígido de
            // recolección de datos (envío, inconveniente, etc.) solo porque su mensaje de
            // apertura contenga una palabra clave como "quiero mi pedido" — eso interrumpe
            // el saludo de bienvenida y se siente agresivo para alguien que recién escribe.
            //
            // Antes acá se llamaba a responderConAgenteIA, que dejaba que la IA respondiera
            // libremente (algo genérico como "¿en qué más te puedo ayudar?") — y como
            // responderConAgenteIA devuelve true cuando responde, procesarWebhook nunca
            // llegaba a mandar el saludo de bienvenida real (el que pide usuario de TikTok +
            // nombre e invita al grupo de WhatsApp). El cliente nuevo terminaba sin ese
            // saludo. Ahora simplemente se deja pasar (return false) para que ese saludo
            // hardcoded sea SIEMPRE la respuesta al primer mensaje, sin depender de si hay
            // IA configurada ni de qué decida responder — es información de onboarding que
            // solo nosotros sabemos (pedir el TikTok, el link del grupo), no algo que la IA
            // pueda inventar. Las intenciones por palabra clave se vuelven a evaluar desde el
            // segundo mensaje en adelante.
            if (primerMensaje) {
                log.info("[TEXTO] Primer mensaje del cliente, se omiten los flujos por palabra clave y se deja el saludo de bienvenida hardcoded");
                return false;
            }
            if (intentDetector.esIntencionInconveniente(contenido)) {
                log.info("[TEXTO] Intención de inconveniente detectada, iniciando flujo INCONVENIENTE");
                iniciarFlujoInconveniente(from, contenido);
                return false;
            }
            if (intentDetector.esIntencionEnvio(contenido)) {
                log.info("[TEXTO] Intención de envío detectada por palabra clave, iniciando flujo ENVIO");
                iniciarFlujoEnvio(from);
                return false;
            }
            if (intentDetector.esIntencionSinFoto(contenido)) {
                log.info("[TEXTO] Cliente avisa que no tiene/no pudo tomar la foto, iniciando flujo PEDIDO por texto");
                iniciarFlujoPedidoTexto(from);
                return false;
            }
            if (intentDetector.esIntencionPedido(contenido)) {
                log.info("[TEXTO] Intención de pedido detectada, iniciando flujo PEDIDO");
                iniciarFlujoPedidoTexto(from);
                return false;
            }
            log.info("[TEXTO] Sin conversación activa ni intención de pedido, intentando agente IA...");
            return responderConAgenteIA(from, contenido);
        }

        if (FLUJO_ENVIO.equals(conv.flujo)) {
            if (intentDetector.esIntencionInconveniente(contenido) || intentDetector.esIntencionPedido(contenido) || intentDetector.esIntencionSinFoto(contenido)) {
                log.info("[TEXTO] Flujo activo pero detectada nueva intención, eliminando conversación vieja");
                stateStore.remove(from);
                return procesarTextoEntrante(from, contenido, false);
            }
            procesarTextoEnvio(from, conv, contenido);
            return false;
        }

        if (FLUJO_INCONVENIENTE.equals(conv.flujo)) {
            if (conv.paso == INC_FOTOS || conv.paso == INC_CONFIRMAR) {
                // Antes esto se delegaba a la IA para "interpretar" el texto libre del cliente
                // en medio del reporte de un daño — pero reportar un inconveniente es justo el
                // tipo de flujo que no debe depender de que un modelo externo interprete bien
                // lo que escribió el cliente (mismo criterio que ya aplicamos a apartar prenda,
                // pagos, envío y horario de live: lo transaccional va por reglas fijas, no por
                // IA). Ahora se maneja igual que los botones "📸 Sí, tengo más" / "✅ Listo,
                // guardar": se detecta por palabra clave si el cliente escribió el equivalente
                // en texto en vez de tocar el botón, y si no reconocemos nada se le recuerda
                // cómo seguir en vez de improvisar una respuesta.
                if (intentDetector.esListo(contenido) || intentDetector.esIntencionSinFoto(contenido)) {
                    log.info("[TEXTO] Flujo inconveniente [{}] paso {}: cliente indica que ya no tiene más fotos, finalizando reporte", from, conv.paso);
                    finalizarInconveniente(from, conv);
                    return false;
                }
                if (conv.paso == INC_CONFIRMAR && intentDetector.esQuiereMasFotos(contenido)) {
                    log.info("[TEXTO] Flujo inconveniente [{}]: cliente confirma que tiene más fotos por enviar", from);
                    conv.paso = INC_FOTOS;
                    enviarMensaje(from, "📸 Perfecto, envíame la siguiente foto del problema.");
                    return false;
                }
                var mensajeRecordatorio = conv.paso == INC_FOTOS
                        ? "📸 Aún necesito la foto del problema para tu reporte. Envíamela cuando puedas, o escribe \"listo\" si prefieres continuar sin foto."
                        : "📸 ¿Tienes otra foto del problema, o ya está listo tu reporte?";
                enviarBotones(from, mensajeRecordatorio,
                    List.of(
                        Map.of("id", "inconveniente_mas_fotos", "title", "📸 Sí, tengo más"),
                        Map.of("id", "inconveniente_listo", "title", "✅ Listo, guardar")
                    ));
                return false;
            }
            procesarTextoInconveniente(from, conv, contenido);
            return false;
        }

        if (FLUJO_PEDIDO.equals(conv.flujo)) {
            // A diferencia de los demás pasos de este flujo, acá SÍ hay que revisar la
            // intención de inconveniente (igual que ya hace FLUJO_ENVIO arriba): antes, un
            // cliente a mitad de apartar una prenda que reportaba un problema ("tengo un
            // inconveniente con mi ropa") no calzaba con ningún if/else de abajo y se quedaba
            // sin ninguna respuesta — el bot simplemente no decía nada.
            if (intentDetector.esIntencionInconveniente(contenido)) {
                log.info("[TEXTO] Flujo PEDIDO activo pero detectada intención de inconveniente, eliminando conversación vieja");
                stateStore.remove(from);
                return procesarTextoEntrante(from, contenido, false);
            }
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
                } else if (intentDetector.esNoSe(contenido)) {
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
            } else if (conv.paso == PEDIDO_ENVIO && (intentDetector.esIntencionPedido(contenido) || intentDetector.esIntencionSinFoto(contenido))) {
                // Ya se registró al menos una prenda y se le preguntó por el envío, pero el
                // cliente quiere apartar una prenda más (con o sin foto) antes de decidir eso.
                // Seguimos en el mismo flujo/conversación (no se pierde nada de lo ya
                // registrado) y le pedimos los datos de la nueva prenda por texto.
                log.info("[TEXTO] Cliente quiere apartar otra prenda dentro del mismo flujo PEDIDO");
                conv.paso = PEDIDO_NOMBRE;
                conv.concepto = null;
                conv.mediaId = null;
                conv.mimeType = null;
                conv.mediaPath = null;
                conv.soportePago = false;
                enviarMensaje(from, "Claro 😊 ¿Qué otra prenda quieres apartar? Escríbela así, por ejemplo: \"jean talla 32\" o \"blusa azul\".");
            }
        }
        return false;
    }

    private boolean responderConAgenteIA(String from, String contenido) {
        var clienteActual = clienteRepo.findByWhatsapp(from).orElse(null);
        if (clienteActual != null && Boolean.TRUE.equals(clienteActual.getRequiereAsesor())) {
            // Ya se marcó que este cliente necesita un asesor humano (queja, pregunta de saldo,
            // etc.). El bot deja de responder libremente para no contradecir al humano que lo
            // está atendiendo; los flujos guiados (envío, pedido, inconveniente) sí siguen
            // activos porque solo recolectan datos y no "opinan" nada.
            // Antes esto dejaba al cliente completamente sin respuesta ante CUALQUIER mensaje
            // (incluso un simple saludo), y parecía que el bot no funcionaba. Ahora se avisa
            // que un asesor lo va a atender, como máximo una vez cada 30 minutos para no
            // repetirlo en cada mensaje que mande mientras espera.
            var ultimoAviso = stateStore.getUltimoAvisoAsesor(from);
            var avisar = ultimoAviso == null || ChronoUnit.MINUTES.between(ultimoAviso, Instant.now()) >= 30;
            if (avisar) {
                enviarMensaje(from, "💜 Ya un asesor está al tanto de tu caso y te va a atender personalmente en breve. Gracias por tu paciencia.");
                stateStore.marcarAvisoAsesor(from);
            }
            log.info("[IA] {} ya requiere asesor humano, el bot no responde libremente (aviso enviado={})", from, avisar);
            return true;
        }

        if (agentesIA.isEmpty()) {
            log.info("[IA] No hay agente IA configurado, respondiendo con opciones básicas para no dejar al cliente sin respuesta");
            enviarBotones(from, "¡Hola! ¿En qué puedo ayudarte? 😊",
                List.of(
                    Map.of("id", "apartar",  "title", "🛍️ Apartar prenda"),
                    Map.of("id", "envio",    "title", "📦 Quiero mi envío"),
                    Map.of("id", "asesora",  "title", "💬 Hablar con asesor")
                ));
            return true;
        }

        var contexto = construirContexto(from);
        log.info("[IA] Contexto construido ({} chars)", contexto.length());

        // Se intenta con cada agente disponible en orden (principal primero, respaldo después).
        // Si el principal falla, se cae, o no devuelve nada, se reintenta automáticamente con
        // el siguiente antes de rendirse — el cliente no debería notar que hubo un problema.
        for (int i = 0; i < agentesIA.size(); i++) {
            var agente = agentesIA.get(i);
            var esRespaldo = i > 0;
            try {
                log.info("[IA] Probando agente {}{}...", agente.nombre(), esRespaldo ? " (respaldo)" : "");
                var respuesta = agente.responder(contenido, contexto);
                if (respuesta.isEmpty()) {
                    log.warn("[IA] {} no devolvió respuesta", agente.nombre());
                    continue;
                }

                var texto = corregirNumeroPago(respuesta.get());
                log.info("[IA] {} respondió: '{}'", agente.nombre(), texto.substring(0, Math.min(100, texto.length())));

                if (texto.contains("[SOLICITAR_ENVIO]")) {
                    log.info("[IA] Detectada solicitud de envío, iniciando flujo ENVIO");
                    var mensajeLimpio = texto.replace("[SOLICITAR_ENVIO]", "").trim();
                    enviarMensaje(from, mensajeLimpio);
                    // Solo se crea el estado del flujo (sin mandar el mensaje de
                    // iniciarFlujoEnvio): el mensaje de arriba, generado por la IA siguiendo
                    // la plantilla del prompt, ya le pidió los datos al cliente.
                    crearConversacionEnvio(from);
                    return true;
                }

                if (texto.contains("[REPORTE_INCONVENIENTE]")) {
                    log.info("[IA] Detectada solicitud de inconveniente, iniciando flujo INCONVENIENTE");
                    iniciarFlujoInconveniente(from, contenido);
                    return true;
                }

                enviarMensaje(from, texto);
                return true;
            } catch (Exception e) {
                log.error("[IA] Error con agente {}{} para {}: {}",
                        agente.nombre(), esRespaldo ? " (respaldo)" : "", from, e.getMessage());
                // sigue el for y prueba el siguiente agente, si hay uno configurado como respaldo
            }
        }

        log.warn("[IA] Ningún agente IA disponible pudo responder, enviando botones de fallback");
        enviarBotones(from, "¿En qué puedo ayudarte?",
            List.of(
                Map.of("id", "apartar",  "title", "🛍️ Apartar prenda"),
                Map.of("id", "envio",    "title", "📦 Quiero mi envío"),
                Map.of("id", "asesora",  "title", "💬 Hablar con asesor")
            ));
        return true;
    }

    /**
     * Qué hacer con una foto que llega por WhatsApp. Ya NO se le pide a ninguna IA que adivine
     * si es una prenda, un comprobante de pago o una foto de daño — se probó con Gemini y con
     * Groq y en la práctica resultó poco confiable para esto: respuestas cortadas a mitad de
     * frase, un bug real donde "DANO" nunca se detectaba, y un modelo de Groq que directamente
     * mandaba su razonamiento interno en inglés como si fuera el mensaje para el cliente. En
     * vez de eso, se le pregunta al cliente con botones (✅ Guardar en baúl / 💳 Registrar pago)
     * — es inmediato, siempre correcto, y es exactamente el mismo flujo que ya se
     * usaba de respaldo cuando no había ningún agente de IA configurado.
     *
     * La única foto que SÍ se recibe directo sin preguntar nada es la de un reporte de
     * inconveniente ya en curso — pero eso tampoco depende de que una IA "adivine" nada: el
     * cliente ya avisó con palabras que tiene un problema (esIntencionInconveniente, que es
     * determinístico) y esto solo sigue ese flujo ya iniciado.
     */
    private void procesarImagenConIA(String from, String mediaId, String mimeType, String caption, String waId) {
        // El caption que viene junto con la foto (ej. "¿cuánto vale esto?") no pasa por
        // procesarTextoEntrante, así que esIntencionPrecio nunca se evaluaba para imágenes: se
        // avisa acá también, sin frenar el procesamiento normal de la foto.
        if (intentDetector.esIntencionPrecio(caption)) {
            log.info("[FOTO] Imagen con caption de intención de precio detectada, escalando a asesor");
            responderEscalarPrecio(from);
        }

        var convActiva = stateStore.get(from);
        // INC_FOTOS: esperando la primera foto. INC_CONFIRMAR: ya llegó al menos una y el
        // cliente puede seguir mandando más sin tocar el botón "Sí, tengo más" (así escribe la
        // mayoría).
        if (convActiva != null && FLUJO_INCONVENIENTE.equals(convActiva.flujo)
                && (convActiva.paso == INC_FOTOS || convActiva.paso == INC_CONFIRMAR)) {
            log.info("[FOTO] Imagen en flujo INCONVENIENTE, guardando directamente");
            recibirFotoInconveniente(from, convActiva, mediaId, mimeType);
            return;
        }

        iniciarFlujoPedidoFoto(from, mediaId, mimeType, waId);
    }

    private void procesarAudioConIA(String from, String mediaId, String mimeType) {
        if (agentesIA.isEmpty()) {
            log.info("[IA] No hay agente IA, el audio queda guardado pero no se interpreta");
            return;
        }

        try {
            var bytes = descargarMediaBytes(mediaId);
            if (bytes == null || bytes.length == 0) {
                log.warn("[IA] No se pudo descargar el audio {}", mediaId);
                enviarMensaje(from, "No pude escuchar tu nota de voz. ¿Puedes escribir el mensaje o intentarlo de nuevo? 🎤");
                return;
            }

            var contexto = construirContexto(from);

            for (int i = 0; i < agentesIA.size(); i++) {
                var agente = agentesIA.get(i);
                var esRespaldo = i > 0;
                try {
                    log.info("[IA] Probando interpretar audio con {}{}...", agente.nombre(), esRespaldo ? " (respaldo)" : "");
                    var respuesta = agente.responderAudio(bytes, mimeType, contexto);
                    if (respuesta.isEmpty()) {
                        log.warn("[IA] {} no pudo interpretar el audio", agente.nombre());
                        continue;
                    }

                    var texto = corregirNumeroPago(respuesta.get());
                    log.info("[IA] {} interpretó el audio: '{}'", agente.nombre(), texto.substring(0, Math.min(100, texto.length())));

                    if (texto.contains("[SOLICITAR_ENVIO]")) {
                        var mensajeLimpio = texto.replace("[SOLICITAR_ENVIO]", "").trim();
                        enviarMensaje(from, mensajeLimpio);
                        // Igual que en responderConAgenteIA: no se manda el mensaje de
                        // iniciarFlujoEnvio, solo se crea el estado, para no duplicar el
                        // mensaje que la IA ya mandó pidiendo los datos.
                        crearConversacionEnvio(from);
                        return;
                    }
                    if (texto.contains("[REPORTE_INCONVENIENTE]")) {
                        iniciarFlujoInconveniente(from, "(reportado por nota de voz)");
                        return;
                    }

                    enviarMensaje(from, texto);
                    return;
                } catch (Exception e) {
                    log.error("[IA] Error interpretando audio con {}{}: {}",
                            agente.nombre(), esRespaldo ? " (respaldo)" : "", e.getMessage());
                }
            }

            log.warn("[IA] Ningún agente pudo interpretar el audio");
            enviarMensaje(from, "No logré entender tu nota de voz 🎤 ¿Puedes escribirlo o intentar de nuevo?");

        } catch (Exception e) {
            log.error("[IA] Error procesando audio con IA para {}", from, e);
        }
    }

    private void procesarUbicacion(String from, String contenido) {
        var conv = stateStore.get(from);
        if (conv != null && FLUJO_ENVIO.equals(conv.flujo) && conv.paso == 3) {
            // El cliente compartió su ubicación justo cuando le pedíamos la dirección: la
            // usamos como respuesta, igual que si la hubiera escrito.
            procesarTextoEnvio(from, conv, contenido.replace("📍 ", "").trim());
        }
    }

    private byte[] descargarMediaBytes(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) return null;
        try {
            var metaUrl = java.net.URI.create("https://graph.facebook.com/v19.0/" + mediaId + "?fields=url,mime_type");
            var conn = (java.net.HttpURLConnection) metaUrl.toURL().openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            var meta = new com.fasterxml.jackson.databind.ObjectMapper().readTree(conn.getInputStream());
            var urlStr = meta.has("url") ? meta.get("url").asText() : null;
            if (urlStr == null) return null;

            var dlConn = (java.net.HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
            dlConn.setRequestProperty("Authorization", "Bearer " + accessToken);
            dlConn.setInstanceFollowRedirects(true);
            dlConn.setConnectTimeout(15000);
            dlConn.setReadTimeout(30000);
            var in = dlConn.getInputStream();
            var buf = new java.io.ByteArrayOutputStream();
            var tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            in.close();
            return buf.toByteArray();
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

        // Saldo de cuenta: le da a la IA algo real con qué responder si preguntan por su
        // deuda o pagos, en vez de tener que escalar siempre a un humano para eso.
        try {
            var cuenta = cuentaRepo.findByCliente_Whatsapp(from).orElse(null);
            if (cuenta != null) {
                var cargos = cuentaMovimientoRepo.sumCargos(cuenta.getId());
                var abonos = cuentaMovimientoRepo.sumAbonos(cuenta.getId());
                long saldo = (cargos != null ? cargos : 0L) - (abonos != null ? abonos : 0L);
                if (saldo > 0) {
                    sb.append("Saldo de cuenta: el cliente debe $").append(saldo).append("\n");
                } else if (saldo < 0) {
                    sb.append("Saldo de cuenta: el cliente tiene a favor $").append(Math.abs(saldo)).append("\n");
                } else {
                    sb.append("Saldo de cuenta: al día, no debe nada\n");
                }
            }
        } catch (Exception e) {
            log.warn("[IA] No se pudo calcular el saldo para el contexto de {}: {}", from, e.getMessage());
        }

        // Historial de pedidos: para que la IA pueda responder "¿en qué va mi pedido?" con
        // datos reales (estado, fecha) en vez de inventar o tener que escalar siempre.
        try {
            var pedidos = pedidoRepo.findByCliente_WhatsappOrderByCreatedAtDesc(from);
            if (pedidos != null && !pedidos.isEmpty()) {
                sb.append("\nPedidos del cliente (más reciente primero):\n");
                pedidos.stream().limit(5).forEach(p -> {
                    sb.append("- Pedido #").append(p.getId() != null ? p.getId().toString().substring(0, 8) : "?");
                    if (p.getEstado() != null) sb.append(" — estado: ").append(p.getEstado());
                    if (p.getCreatedAt() != null) sb.append(" — creado: ").append(p.getCreatedAt());
                    sb.append("\n");
                });
            }
        } catch (Exception e) {
            log.warn("[IA] No se pudo cargar el historial de pedidos para el contexto de {}: {}", from, e.getMessage());
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

    private void procesarTextoEnvio(String from, ConversacionCliente conv, String contenido) {
        if (contenido.contains("\n") && contenido.lines().count() >= 3) {
            var lineas = contenido.lines().map(String::trim).filter(l -> !l.isBlank()).toList();
            var datos = envioService.crearDesdeTexto(from, contenido);

            if (datos.getNombreCompleto() == null || datos.getNombreCompleto().isBlank()) {
                if (lineas.size() >= 6) {
                    datos = envioService.crearConDatos(from,
                        lineas.get(0), lineas.get(1), lineas.get(2),
                        lineas.get(3), lineas.get(4), lineas.get(5));
                } else if (lineas.size() >= 5) {
                    datos = envioService.crearConDatos(from,
                        lineas.get(0), lineas.get(1), lineas.get(2),
                        lineas.get(3), lineas.get(4), "");
                }
            }

            stateStore.remove(from);
            enviarMensaje(from, """
                ✅ ¡Gracias! Hemos recibido tus datos de envío:
                • Nombre: %s
                • Teléfono: %s
                • Cédula: %s
                • Dirección: %s
                • Ciudad: %s
                • Barrio: %s

                En breve te contactaremos para coordinar la entrega.""".formatted(
                datos.getNombreCompleto(), datos.getTelefono(), datos.getCedula(),
                datos.getDireccion(), datos.getCiudad(), datos.getBarrio()));
            log.info("Flujo envío [{}] completado en un solo mensaje", from);
            return;
        }

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
                conv.paso = ENVIO_CONFIRMAR;
                pedirConfirmacionEnvio(from, conv);
            }
            case ENVIO_CONFIRMAR -> {
                // El cliente escribió texto en vez de tocar un botón; se le recuerda cómo confirmar.
                pedirConfirmacionEnvio(from, conv);
            }
        }
        log.info("Flujo envío [{}] paso {}: {}", from, conv.paso, contenido);
    }

    private void pedirConfirmacionEnvio(String from, ConversacionCliente conv) {
        enviarBotones(from, """
            Por favor confirma que tus datos estén correctos antes de enviarlos:

            • Nombre: %s
            • Teléfono: %s
            • Cédula: %s
            • Dirección: %s
            • Ciudad: %s
            • Barrio: %s""".formatted(
                conv.nombre, conv.telefono, conv.cedula, conv.direccion, conv.ciudad, conv.barrio),
            List.of(
                Map.of("id", "envio_confirmar", "title", "✅ Confirmar"),
                Map.of("id", "envio_reiniciar",  "title", "🔄 Corregir todo")
            ));
        log.info("Flujo envío [{}]: pidiendo confirmación de datos", from);
    }


    private void responderEscalarPrecio(String from) {
        clienteRepo.findByWhatsapp(from).ifPresent(c -> {
            c.setRequiereAsesor(true);
            clienteRepo.save(c);
        });
        enviarMensaje(from, "💜 El precio exacto te lo confirma un asesor en un momento. ¿Hay algo más en lo que te pueda ayudar mientras tanto?");
    }

    // Único lugar donde vive el número de pago: si algún día cambia, se edita solo acá.
    private static final String NUMERO_PAGO = "3229063035";

    private void responderComoPagar(String from) {
        enviarMensaje(from, """
            💳 Los pagos los recibimos por *Nequi* o *Daviplata* al número *%s*.
            Cuando hagas la transferencia, envíanos la foto del comprobante para confirmarlo. 💜""".formatted(NUMERO_PAGO));
        log.info("[PAGO] Cliente {} consultó cómo/dónde pagar, se dio el número de Nequi/Daviplata", from);
    }

    // Único lugar donde vive el link del grupo de WhatsApp: si algún día cambia, se edita solo
    // acá (lo usan responderHorarioLive y el saludo de bienvenida a clientes nuevos).
    private static final String LINK_GRUPO_WHATSAPP = "https://chat.whatsapp.com/GjRsIMvvmGn4daCbsyspTz";

    // No sabemos con certeza cuándo va a ser el próximo live (se define y anuncia aparte, no
    // hay una agenda fija en el sistema), así que no se inventa fecha/hora ni se escala a un
    // asesor: se le indica al cliente que esté pendiente a las publicaciones y, sobre todo, que
    // se una al grupo de WhatsApp, que es donde se avisa el día y la hora de cada live.
    private void responderHorarioLive(String from) {
        enviarMensaje(from, """
            🎥 ¡Estate pendiente a nuestras publicaciones! Ahí anunciamos cuándo va a ser el próximo live.

            El día y la hora exacta los avisamos en nuestro grupo de WhatsApp, únete acá 👇
            %s""".formatted(LINK_GRUPO_WHATSAPP));
        log.info("[LIVE] Cliente {} preguntó por el horario del live, se invitó al grupo de WhatsApp", from);
    }

    // Único lugar donde viven los días de despacho: si algún día cambian, se edita solo acá.
    // No incluye ciudad de origen ni hora exacta porque eso no está confirmado — mejor no decir
    // nada que inventar un dato (fue justo el bug que corrigió este método: la IA respondía
    // "martes y viernes desde Bogotá" y el "desde Bogotá" era inventado).
    private void responderHorarioEnvios(String from) {
        enviarMensaje(from, """
            📦 Los envíos se despachan los *martes y viernes*. No manejamos una hora fija, así que en cuanto salga tu pedido te avisamos. 💜""");
        log.info("[ENVIO] Cliente {} preguntó por el horario general de despacho, se respondió con los días fijos", from);
    }

    /**
     * Red de seguridad para cuando la respuesta la generó la IA en vez de nuestro texto fijo:
     * los modelos a veces truncan o alteran números largos al redactar (por ejemplo, el
     * número de pago llegó cortado como "322906" en vez de "3229063035"). Si la respuesta
     * menciona Nequi o Daviplata pero no trae el número completo y correcto, se reemplaza por
     * el mensaje fijo para garantizar que el cliente siempre reciba el número bien.
     */
    private String corregirNumeroPago(String texto) {
        if (texto == null) return texto;
        var lower = texto.toLowerCase();
        var mencionaPago = lower.contains("nequi") || lower.contains("daviplata");
        if (mencionaPago && !texto.contains(NUMERO_PAGO)) {
            log.warn("[IA] La respuesta menciona Nequi/Daviplata pero no trae el número completo, se corrige. Original: '{}'", texto);
            return """
                💳 Los pagos los recibimos por *Nequi* o *Daviplata* al número *%s*.
                Cuando hagas la transferencia, envíanos la foto del comprobante para confirmarlo. 💜""".formatted(NUMERO_PAGO);
        }
        return texto;
    }

    private void responderSaldo(String from) {
        clienteRepo.findByWhatsapp(from).ifPresent(c -> {
            c.setRequiereAsesor(true);
            clienteRepo.save(c);
        });

        var cuenta = cuentaRepo.findByCliente_Whatsapp(from).orElse(null);
        if (cuenta != null) {
            var cargos = cuentaMovimientoRepo.sumCargos(cuenta.getId());
            var abonos = cuentaMovimientoRepo.sumAbonos(cuenta.getId());
            long saldo = (cargos != null ? cargos : 0L) - (abonos != null ? abonos : 0L);

            if (saldo > 0) {
                enviarMensaje(from, String.format(
                    "💰 Tu saldo pendiente es de *$%,d*. Un asesor revisará tu cuenta y te confirmará los detalles. ¿Deseas realizar un abono? 💜", saldo));
            } else if (saldo < 0) {
                enviarMensaje(from, String.format(
                    "💰 Tu cuenta muestra un saldo a favor de *$%,d*. Un asesor revisará y te confirmará. 💜", Math.abs(saldo)));
            } else {
                enviarMensaje(from, "💰 No tienes saldo pendiente. ¡Estás al día! 🎉 Un asesor puede ayudarte si tienes dudas.");
            }
        } else {
            enviarMensaje(from, "💬 Un asesor revisará tu cuenta y te confirmará tu saldo. En breve te atendemos. 💜");
        }
        log.info("[SALDO] Consulta de saldo desde {}, requiereAsesor=true", from);
    }

    private void iniciarFlujoPedidoFoto(String from, String mediaId, String mimeType, String waId) {
        var convExistente = stateStore.get(from);
        if (convExistente != null && FLUJO_PEDIDO.equals(convExistente.flujo)) {
            // Ya hay una prenda en proceso (esperando botón o precio): esta foto se encola en
            // vez de reemplazar la conversación, para no perder lo que ya se venía registrando
            // (así el cliente puede mandar varias fotos seguidas y se le pregunta por cada una,
            // una por una, sin que se pisen entre sí).
            if (convExistente.prendasPendientes == null) {
                convExistente.prendasPendientes = new java.util.ArrayList<>();
            }
            var pendiente = new PrendaPendiente();
            pendiente.mediaId = mediaId;
            pendiente.mimeType = mimeType;
            pendiente.waMessageId = waId;
            convExistente.prendasPendientes.add(pendiente);
            enviarMensaje(from, "📸 Recibí otra foto. La registro apenas terminemos con la prenda anterior (tienes %d en cola). 😊"
                    .formatted(convExistente.prendasPendientes.size()));
            return;
        }

        var conv = new ConversacionCliente();
        conv.flujo = FLUJO_PEDIDO;
        conv.paso = PEDIDO_CONFIRMAR_FOTO;
        conv.mediaId = mediaId;
        conv.mimeType = mimeType;
        stateStore.put(from, conv);
        enviarBotones(from, """
            📸 Recibí tu foto. ¿Qué quieres hacer?""",
            List.of(
                Map.of("id", "si_foto",       "title", "✅ Guardar en baúl"),
                Map.of("id", "soporte_pago",  "title", "💳 Registrar pago")
            ));
    }

    private void iniciarFlujoPedidoTexto(String from) {
        var conv = new ConversacionCliente();
        conv.flujo = FLUJO_PEDIDO;
        conv.paso = PEDIDO_NOMBRE;
        stateStore.put(from, conv);
        enviarMensaje(from, """
            Claro 😊 ¿Qué prenda quieres apartar?
            Escríbela así, por ejemplo: "jean talla 32" o "blusa azul".""");
    }

    private void iniciarFlujoEnvio(String from) {
        // Si ya está configurado el Flow de "Datos de envío" (whatsapp.flow-envio-id), se manda
        // el formulario nativo de WhatsApp en una sola pantalla, en vez del ir y venir de
        // preguntas de texto de abajo. Mientras esa propiedad esté vacía, el comportamiento no
        // cambia: sigue siendo el flujo paso a paso de siempre.
        if (flowEnvioId != null && !flowEnvioId.isBlank()) {
            enviarFlujoEnvio(from);
            return;
        }
        crearConversacionEnvio(from);
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

    @Override
    public void enviarFlujoEnvio(String destinatario) {
        var body = Map.of(
            "messaging_product", "whatsapp",
            "to", destinatario,
            "type", "interactive",
            "interactive", Map.of(
                "type", "flow",
                "body", Map.of("text", "Para coordinar tu envío necesito estos datos. Tócalo, es rapidito 👇"),
                "action", Map.of(
                    "name", "flow",
                    "parameters", Map.of(
                        "flow_message_version", "3",
                        "flow_token", UUID.randomUUID().toString(),
                        "flow_id", flowEnvioId,
                        "flow_cta", "Completar datos",
                        "flow_action", "navigate",
                        "flow_action_payload", Map.of("screen", "DATOS_ENVIO")
                    )
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
            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(destinatario)
                    .contenido("[Formulario de datos de envío enviado]")
                    .tipo("interactive")
                    .direccion("SALIDA")
                    .waMessageId(r.get("messages").get(0).get("id").asText())
                    .build());
            log.info("[ENVIO] Flow de datos de envío enviado a {}", destinatario);
        } catch (Exception e) {
            log.error("Error enviando Flow de envío a {}", destinatario, e);
            throw new RuntimeException("Error enviando Flow de envío WhatsApp: " + e.getMessage(), e);
        }
    }

    /**
     * Procesa la respuesta del Flow "Datos de envío" cuando el cliente lo completa y toca
     * "Enviar". No usa ConversationStateStore: al ser un formulario en una sola pantalla, todos
     * los datos llegan juntos en un solo mensaje, sin necesidad de recordar un paso anterior.
     */
    private void procesarRespuestaFlujoEnvio(String from, String responseJson) {
        try {
            var datos = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseJson);
            var nombre    = datos.hasNonNull("nombre")    ? datos.get("nombre").asText()    : "";
            var telefono  = datos.hasNonNull("telefono")  ? datos.get("telefono").asText()  : "";
            var cedula    = datos.hasNonNull("cedula")    ? datos.get("cedula").asText()    : "";
            var direccion = datos.hasNonNull("direccion") ? datos.get("direccion").asText() : "";
            var ciudad    = datos.hasNonNull("ciudad")    ? datos.get("ciudad").asText()    : "";
            var barrio    = datos.hasNonNull("barrio")    ? datos.get("barrio").asText()    : "";

            envioService.crearConDatos(from, nombre, telefono, cedula, direccion, ciudad, barrio);
            enviarMensaje(from, """
                ✅ ¡Gracias! Hemos recibido tus datos de envío.
                En breve te contactaremos para coordinar la entrega.""");
            log.info("[ENVIO] Formulario (Flow) de envío recibido y guardado para {}", from);
        } catch (Exception e) {
            log.error("[ENVIO] No se pudo procesar la respuesta del Flow de envío de {}: {}", from, e.getMessage(), e);
            enviarMensaje(from, """
                😕 Tuvimos un problema guardando tus datos. Por favor intenta de nuevo o escríbenos y te ayudamos manualmente. 💜""");
        }
    }

    /**
     * Solo crea el estado de conversación del flujo ENVIO, sin mandar ningún mensaje. Se usa
     * cuando el agente IA ya detectó [SOLICITAR_ENVIO] y su propia respuesta (que sigue la
     * plantilla del prompt) ya le pidió los datos al cliente — si además se llamara a
     * iniciarFlujoEnvio() de ahí, el cliente recibía DOS mensajes distintos pidiendo lo mismo.
     */
    private void crearConversacionEnvio(String from) {
        var conv = new ConversacionCliente();
        conv.flujo = FLUJO_ENVIO;
        conv.paso = 0;
        stateStore.put(from, conv);
    }

    private void iniciarFlujoInconveniente(String from, String descripcionInicial) {
        var conv = new ConversacionCliente();
        conv.flujo = FLUJO_INCONVENIENTE;
        conv.paso = INC_DESCRIPCION;
        conv.descripcion = descripcionInicial != null ? descripcionInicial : "";
        conv.fotosInconveniente = new java.util.ArrayList<>();
        stateStore.put(from, conv);
        enviarMensaje(from, """
            Lamento mucho escuchar eso. Para poder ayudarte, cuéntame con más detalle: \
            ¿qué problema tienes con tu pedido? 📝""");
        log.info("Iniciado flujo inconveniente para {} con descripción: {}", from, descripcionInicial);
    }

    private void procesarTextoInconveniente(String from, ConversacionCliente conv, String contenido) {
        if (conv.paso == INC_DESCRIPCION) {
            conv.descripcion = contenido;
            conv.paso = INC_FOTOS;
            enviarMensaje(from, """
                Gracias por la información. Ahora por favor envíame las fotos del problema \
                (prenda dañada, faltante, etc.). 📷""");
        }
        log.info("Flujo inconveniente [{}] paso {}: {}", from, conv.paso, contenido);
    }

    private void recibirFotoInconveniente(String from, ConversacionCliente conv, String mediaId, String mimeType) {
        recibirFotoInconveniente(from, conv, descargarMediaLocal(mediaId));
    }

    private void recibirFotoInconveniente(String from, ConversacionCliente conv, byte[] bytes, String mimeType) {
        recibirFotoInconveniente(from, conv, guardarMediaLocal(bytes, mimeType));
    }

    private void recibirFotoInconveniente(String from, ConversacionCliente conv, String fotoPath) {
        if (conv.fotosInconveniente == null) conv.fotosInconveniente = new java.util.ArrayList<>();
        if (fotoPath != null) conv.fotosInconveniente.add(fotoPath);
        conv.paso = INC_CONFIRMAR;
        // Si no se pudo guardar la foto se le avisa al cliente en vez de decirle "Foto recibida"
        // como si nada — antes quedaba guardado el reporte con 0 fotos sin que nadie se enterara.
        var mensaje = fotoPath != null
            ? "📸 Foto recibida. ¿Tienes más fotos?"
            : "⚠️ Esa foto no se pudo guardar, ¿puedes intentar enviarla de nuevo? También puedes seguir sin ella.";
        enviarBotones(from, mensaje,
            List.of(
                Map.of("id", "inconveniente_mas_fotos", "title", "📸 Sí, tengo más"),
                Map.of("id", "inconveniente_listo", "title", "✅ Listo, guardar")
            ));
        log.info("Foto inconveniente para {}: {} (total: {})", from, fotoPath, conv.fotosInconveniente.size());
    }

    private void finalizarInconveniente(String from, ConversacionCliente conv) {
        var inc = inconvenienteService.crear(
            from, "OTRO", conv.descripcion, null, conv.fotosInconveniente);
        stateStore.remove(from);
        // Un inconveniente siempre debe quedar visible como "requiere atención humana"
        // en el panel de WhatsApp, no solo en la pantalla de Inconvenientes.
        clienteRepo.findByWhatsapp(from).ifPresent(c -> {
            c.setRequiereAsesor(true);
            clienteRepo.save(c);
        });
        enviarMensaje(from, """
            ✅ Hemos registrado tu reporte (#%s). Nuestro equipo lo revisará pronto. \
            ¡Gracias por avisarnos! 💜""".formatted(inc.getId().toString().substring(0, 8)));
        log.info("Inconveniente creado desde {}: {} tipo=OTRO fotos={} requiereAsesor=true",
            from, inc.getId(), conv.fotosInconveniente != null ? conv.fotosInconveniente.size() : 0);
    }

    private void finalizarInconvenienteDanado(String from, ConversacionCliente conv) {
        var inc = inconvenienteService.crear(
            from, "DANO", conv.descripcion, null, conv.fotosInconveniente);
        stateStore.remove(from);
        enviarMensaje(from, """
            ✅ Hemos registrado tu reporte de prenda dañada (#%s). Nuestro equipo lo revisará pronto. \
            ¡Gracias por avisarnos! 💜""".formatted(inc.getId().toString().substring(0, 8)));
        log.info("Inconveniente creado desde {}: {} tipo=DANO fotos={}",
            from, inc.getId(), conv.fotosInconveniente != null ? conv.fotosInconveniente.size() : 0);
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

        // Si hay más prendas en cola (otras fotos que mandó mientras registrábamos esta, o
        // prendas sin foto que agregó en el camino), seguimos preguntando por la siguiente en
        // vez de pasar de una vez a preguntar por el envío.
        if (avanzarSiguientePrendaPendiente(from, conv)) {
            return;
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
        if (valor != null) {
            enviarMensaje(from, "💳 ¡Listo! Registramos tu soporte de pago por $%d. La foto quedó en tu cuenta y la revisaremos para confirmarla. 💜"
                    .formatted(valor));
        } else {
            enviarMensaje(from, "💳 ¡Listo! Registramos tu comprobante de pago. La foto quedó en tu cuenta y la revisaremos para confirmarla. 💜");
        }
        if (!avanzarSiguientePrendaPendiente(from, conv)) {
            stateStore.remove(from);
        }
    }

    private String descargarMediaLocal(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            log.warn("[MEDIA] descargarMediaLocal llamado sin mediaId (null o vacío)");
            return null;
        }
        try {
            var meta = whatsappWebClient.get()
                .uri("/{mediaId}", mediaId)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
            var urlStr = meta.has("url") ? meta.get("url").asText() : null;
            if (urlStr == null) {
                log.warn("[MEDIA] Meta no devolvió 'url' al consultar metadata de {}: {}", mediaId, meta);
                return null;
            }
            var mime = meta.has("mime_type") ? meta.get("mime_type").asText() : "image/jpeg";

            var bytes = whatsappWebClient.get()
                .uri(urlStr)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
            return guardarMediaLocal(bytes, mime);
        } catch (Exception e) {
            log.warn("No se pudo descargar media {} local: {}", mediaId, e.getMessage());
            return null;
        }
    }

    /**
     * Guarda en disco unos bytes ya descargados (evita volver a pedirle la imagen a Meta
     * cuando ya la tenemos en memoria, por ejemplo tras {@link #descargarMediaBytes}) —
     * la URL de descarga de Meta es de corta duración y pedirla dos veces para la misma
     * imagen podía fallar en silencio, perdiendo la foto.
     */
    private String guardarMediaLocal(byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0) {
            log.warn("[MEDIA] guardarMediaLocal llamado con bytes vacíos o null (mimeType={})", mimeType);
            return null;
        }
        try {
            var ext = extensionSegunMime(mimeType);
            var fileName = UUID.randomUUID() + "." + ext;
            var targetDir = Paths.get(mediaDir, "cuentas");
            Files.createDirectories(targetDir);
            Files.write(targetDir.resolve(fileName), bytes);
            log.info("Media guardada local como {}", fileName);
            return "cuentas/" + fileName;
        } catch (Exception e) {
            log.warn("No se pudo guardar media local: {}", e.getMessage());
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
        enviarMensaje(destinatario, texto, null);
    }

    @Override
    public void enviarMensaje(String destinatario, String texto, String replyToWaMessageId) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("messaging_product", "whatsapp");
        body.put("to", destinatario);
        body.put("type", "text");
        body.put("text", Map.of("body", texto));
        var citando = replyToWaMessageId != null && !replyToWaMessageId.isBlank();
        if (citando) {
            // "context.message_id" es lo que hace que WhatsApp muestre este mensaje como una
            // respuesta citando al original (con su vista previa arriba), igual que el botón
            // de "Responder" en WhatsApp Web.
            body.put("context", Map.of("message_id", replyToWaMessageId));
        }

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
                    .contextWaMessageId(citando ? replyToWaMessageId : null)
                    .build());
        } catch (Exception e) {
            log.error("Error enviando WA a {}", destinatario, e);
            throw new RuntimeException("Error enviando mensaje WhatsApp: " + e.getMessage(), e);
        }
    }

    @Override
    public void enviarBotones(String destinatario, String texto, List<Map<String, String>> botones) {
        enviarBotones(destinatario, texto, botones, null);
    }

    @Override
    public void enviarBotones(String destinatario, String texto, List<Map<String, String>> botones, String replyToWaMessageId) {
        var buttons = botones.stream()
            .map(b -> Map.of(
                "type", "reply",
                "reply", Map.of("id", b.get("id"), "title", truncar(b.get("title"), 20))
            ))
            .toList();

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("messaging_product", "whatsapp");
        body.put("to", destinatario);
        body.put("type", "interactive");
        body.put("interactive", Map.of(
            "type", "button",
            "body", Map.of("text", texto),
            "action", Map.of("buttons", buttons)
        ));
        var citando = replyToWaMessageId != null && !replyToWaMessageId.isBlank();
        if (citando) {
            body.put("context", Map.of("message_id", replyToWaMessageId));
        }

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
                    .contextWaMessageId(citando ? replyToWaMessageId : null)
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
    public String enviarPlantillaMeta(String destinatario, String nombrePlantilla, String idioma, List<String> valoresVariables) {
        var parametros = valoresVariables.stream()
            .map(v -> (Object) Map.of("type", "text", "text", v))
            .toArray();

        var componentes = parametros.length == 0
            ? new Object[]{}
            : new Object[]{ Map.of("type", "body", "parameters", parametros) };

        var body = Map.of(
            "messaging_product", "whatsapp",
            "to", destinatario,
            "type", "template",
            "template", Map.of(
                "name", nombrePlantilla,
                "language", Map.of("code", idioma == null || idioma.isBlank() ? "es" : idioma),
                "components", componentes
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

            var waMessageId = r.get("messages").get(0).get("id").asText();
            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(destinatario)
                    .contenido("[Plantilla: " + nombrePlantilla + "] " + String.join(" · ", valoresVariables))
                    .tipo("template")
                    .direccion("SALIDA")
                    .waMessageId(waMessageId)
                    .build());
            log.info("[BROADCAST] Plantilla '{}' enviada a {}", nombrePlantilla, destinatario);
            return waMessageId;
        } catch (Exception e) {
            log.error("[BROADCAST] Error enviando plantilla '{}' a {}: {}", nombrePlantilla, destinatario, e.getMessage());
            throw new RuntimeException("Error enviando plantilla WhatsApp: " + e.getMessage(), e);
        }
    }

    @Override
    public void enviarAvisoEnviadoSinGuia(String destinatario, String nombre) {
        var nombreSaludo = (nombre == null || nombre.isBlank()) ? "" : " " + nombre;
        enviarMensaje(destinatario, """
            📦 ¡Tu pedido ya fue enviado%s! ✅

            En los próximos días te compartimos el número de guía por este mismo chat para que puedas hacerle seguimiento.

            ¡Gracias por tu compra! 💜""".formatted(nombreSaludo));
        log.info("[ENVIO] Aviso de envío (sin guía) enviado a {}", destinatario);
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

    @Override
    public void borrarConversacion(String whatsappFrom) {
        // Limpia también el estado en memoria del flujo con el bot (paso actual,
        // último recordatorio, etc.) para que no quede "colgado" a mitad de un flujo
        // si el mismo número vuelve a escribir después de borrar el historial.
        stateStore.limpiarTodo(whatsappFrom);
        var borrados = mensajeRepo.deleteByWhatsappFrom(whatsappFrom);
        log.info("[WA] Conversación borrada por completo para {} ({} mensajes eliminados)", whatsappFrom, borrados);
    }
}
