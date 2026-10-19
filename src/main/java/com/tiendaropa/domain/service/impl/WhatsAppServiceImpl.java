package com.tiendaropa.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.SolicitudEnvio;
import com.tiendaropa.domain.model.WaMensaje;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.CuentaRepository;
import com.tiendaropa.domain.repository.CuentaMovimientoRepository;
import com.tiendaropa.domain.repository.PedidoRepository;
import com.tiendaropa.domain.repository.SolicitudEnvioRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.CoexistenciaService;
import com.tiendaropa.domain.service.CuentaService;
import com.tiendaropa.domain.service.HorarioAtencion;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.tiendaropa.domain.service.whatsapp.WhatsAppCloudClient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppServiceImpl implements WhatsAppService {

    @Value("${whatsapp.media-dir:./media}")
    private String mediaDir;

    // ID del Flow de "Datos de envío" en WhatsApp Manager. Mientras esté vacío (no configurado),
    // iniciarFlujoEnvio() sigue usando las preguntas de texto de siempre — así este cambio no
    // afecta nada hasta que el Flow esté creado y publicado y se pegue su ID acá.
    @Value("${whatsapp.flow-envio-id:}")
    private String flowEnvioId;

    // Coexistencia: cuando alguien del equipo le responde a un cliente desde la app de WhatsApp
    // Business del celular, el bot deja de contestarle a ese cliente por estos minutos.
    @Value("${whatsapp.coexistencia.pausa-bot-minutos:60}")
    private long pausaBotMinutos;

    private final WhatsAppCloudClient cloud;

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
    private final CoexistenciaService coexistencia;
    private final HorarioAtencion horario;

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

    // Cuánto tiempo hacia atrás se mira lo que escribió el cliente antes de una foto para
    // entender de qué se trata (ej. "no me llegó esta prenda" y luego la foto).
    private static final int MINUTOS_CONTEXTO_FOTO = 10;

    // Un candado por número: los webhooks corren en paralelo (@Async), y un texto y una foto
    // del mismo cliente podían procesarse a la vez pisándose el estado de la conversación
    // (ej. el texto abría el reporte de inconveniente y la foto, en paralelo, lo reemplazaba
    // por el flujo de apartar). Así los mensajes de un mismo número se procesan de a uno.
    private final ConcurrentHashMap<String, ReentrantLock> candadosPorNumero = new ConcurrentHashMap<>();


    // Meta reintenta (y reenvía duplicado) un webhook si no le respondemos rápido, y este
    // método puede tardar varios segundos por la descarga de imágenes + análisis con la IA.
    // Con @Async, el controller le responde "recibido" a Meta de inmediato mientras esto
    // corre en segundo plano — así se evitan los reintentos/duplicados en vez de solo
    // tolerarlos (ver el índice único de wa_message_id, que sigue como red de seguridad).
    @Override
    @Async
    public void procesarWebhook(JsonNode payload) {
        try {
            if (procesarCamposCoexistencia(payload)) return;

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
            var candado = candadosPorNumero.computeIfAbsent(from, k -> new ReentrantLock());
            candado.lock();
            try {
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

                    if (atendidoDesdeElCelular(from)) {
                        // Una persona del equipo está conversando con este cliente desde la app
                        // del celular: el mensaje queda en el panel, pero el bot no interviene.
                        log.info("[BOT] {} está siendo atendido desde el celular, el bot no responde", from);
                        return;
                    }

                    // Va antes que cualquier flujo, la IA o el saludo de bienvenida: si el cliente
                    // pide una persona, el bot no debe contestarle nada más (ni aunque esté a mitad
                    // de un flujo de envío o de apartar).
                    if ((tipo.equals("text") || tipo.equals("image")) && intentDetector.esIntencionHablarConAsesor(contenido)) {
                        pasarAAsesor(from);
                        return;
                    }

                    if (tipo.equals("text") && intentDetector.esCancelarAvisosLive(contenido)) {
                        cambiarAvisosLive(from, false);
                        enviarMensaje(from, "Listo, ya no te enviaremos avisos de lives.");
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
                                ¡Hola! Bienvenido/a a Patio de Ropa Jireh 💜
                                ¿Me compartes tu nombre y tu usuario de TikTok?

                                Avisamos los lives en nuestro grupo: %s""".formatted(LINK_GRUPO_WHATSAPP));
                        }
                    }
                } finally {
                    stateStore.marcarInteraccion(from);
                    stateStore.persistir(from);
                }
            } finally {
                candado.unlock();
            }

        } catch (Exception e) {
            log.error("Error procesando webhook WA", e);
        }
    }

    /**
     * Webhooks propios de la coexistencia (número usado a la vez en la app del celular y en la
     * API). Devuelve true si el payload era de ese tipo y ya se procesó, para que procesarWebhook
     * no lo trate como un mensaje entrante normal.
     */
    private boolean procesarCamposCoexistencia(JsonNode payload) {
        var procesado = false;
        for (var entry : payload.path("entry")) {
            for (var cambio : entry.path("changes")) {
                var campo = cambio.path("field").asText("");
                var value = cambio.path("value");
                try {
                    switch (campo) {
                        case "history" -> {
                            coexistencia.procesarHistorial(value);
                            procesado = true;
                        }
                        case "smb_app_state_sync" -> {
                            coexistencia.procesarContactos(value);
                            procesado = true;
                        }
                        case "smb_message_echoes" -> {
                            for (var eco : value.path("message_echoes")) {
                                registrarRespuestaDesdeCelular(eco);
                            }
                            procesado = true;
                        }
                        default -> { }
                    }
                } catch (Exception e) {
                    log.error("[COEXISTENCIA] Error procesando webhook '{}'", campo, e);
                    procesado = true;
                }
            }
        }
        return procesado;
    }

    /**
     * Alguien del equipo le respondió a un cliente desde la app del celular. Se guarda en el panel
     * y, con el mismo candado que usan los mensajes del cliente, se pausa el bot para ese número y
     * se descarta cualquier flujo que el bot tuviera a medias: lo está atendiendo una persona.
     */
    private void registrarRespuestaDesdeCelular(JsonNode eco) {
        var numeroCliente = coexistencia.registrarEco(eco);
        if (numeroCliente == null) return;
        var candado = candadosPorNumero.computeIfAbsent(numeroCliente, k -> new ReentrantLock());
        candado.lock();
        try {
            stateStore.recuperarSiNecesario(numeroCliente);
            stateStore.marcarRespuestaHumana(numeroCliente);
            stateStore.remove(numeroCliente);
            stateStore.persistir(numeroCliente);
        } finally {
            candado.unlock();
        }
    }

    /**
     * El cliente pidió hablar con una persona (botón, texto o nota de voz). Se le avisa una sola
     * vez y el bot queda silenciado para ese número: ni IA, ni flujos, ni saludos, ni avisos de
     * "ya un asesor está al tanto". Solo un asesor lo reactiva desde el panel (silenciar-bot).
     */
    private void pasarAAsesor(String from) {
        stateStore.remove(from);
        var cliente = clienteRepo.findByWhatsapp(from)
                .orElseGet(() -> clienteRepo.save(Cliente.builder().whatsapp(from).build()));
        cliente.setRequiereAsesor(true);
        cliente.setBotSilenciado(true);
        clienteRepo.save(cliente);
        // Si el número es nuevo, sus mensajes se guardaron sin cliente y el panel no vería que
        // el bot quedó silenciado.
        mensajeRepo.vincularCliente(cliente, from);
        enviarMensaje(from, horario.estaAbierto()
                ? "💬 Te comunico con un asesor. En breve una persona del equipo te atiende por este chat."
                : "💬 Te comunico con un asesor. Atendemos de %s, así que te escribimos %s."
                        .formatted(horario.descripcion(), horario.cuandoTeAtendemos()));
        log.info("[ASESOR] {} pidió hablar con un asesor: bot silenciado hasta que lo reactiven desde el panel", from);
    }

    private boolean atendidoDesdeElCelular(String from) {
        var ultima = stateStore.getUltimaRespuestaHumana(from);
        return ultima != null && ChronoUnit.MINUTES.between(ultima, Instant.now()) < pausaBotMinutos;
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
        // El id de este botón lleva el id del movimiento a deshacer (ver registrarCargoDesdeBot y confirmarAbonoFoto),
        // así funciona aunque la conversación ya haya terminado o avanzado a otra prenda.
        if (tipo.startsWith("button_deshacer_")) {
            deshacerRegistroFoto(from, tipo.substring("button_deshacer_".length()));
            return;
        }
        switch (tipo) {
            case "button_envio" -> iniciarFlujoEnvio(from);
            case "button_resumen_ok" -> enviarMensaje(from,
                    "Perfecto ✅ Tu pedido sale el próximo día de despacho (martes o viernes). Te avisamos con la guía.");
            case "button_resumen_revisar" -> {
                clienteRepo.findByWhatsapp(from).ifPresent(c -> {
                    c.setRequiereAsesor(true);
                    clienteRepo.save(c);
                });
                enviarMensaje(from, "Un asesor revisa tu pedido antes de enviarlo y te escribe " + horario.cuandoTeAtendemos() + ".");
            }
            case "button_lives_si" -> {
                cambiarAvisosLive(from, true);
                enviarMensaje(from, "Listo 🔔 Te avisamos por aquí cuando haya live. Si ya no quieres avisos, escribe *no más avisos*.");
            }
            case "button_lives_no" -> enviarMensaje(from, "Perfecto, no te enviaremos avisos de lives.");
            case "button_apartar" -> {
                stateStore.remove(from);
                iniciarFlujoPedidoTexto(from);
            }
            case "button_asesora" -> pasarAAsesor(from);
            // Al guardar en baúl o registrar un pago con foto ya NO se le pregunta el valor al
            // cliente (tomaba tiempo y confundía): el cargo queda "valor por definir" y el abono
            // "por validar", y el operador pone el valor desde el panel de Cuentas.
            case "button_si_foto" -> {
                var conv = stateStore.get(from);
                if (conv != null && FLUJO_PEDIDO.equals(conv.flujo) && conv.paso == PEDIDO_CONFIRMAR_FOTO) {
                    registrarCargoDesdeBot(from, conv, null);
                }
            }
            case "button_soporte_pago" -> {
                var conv = stateStore.get(from);
                if (conv != null && FLUJO_PEDIDO.equals(conv.flujo) && conv.paso == PEDIDO_CONFIRMAR_FOTO) {
                    conv.soportePago = true;
                    registrarAbonoDesdeBot(from, conv, null);
                }
            }
            case "button_foto_problema" -> {
                var conv = stateStore.get(from);
                if (conv != null && FLUJO_PEDIDO.equals(conv.flujo) && conv.paso == PEDIDO_CONFIRMAR_FOTO) {
                    var fotos = fotosDelPedidoEnCurso(conv);
                    stateStore.remove(from);
                    iniciarInconvenienteConFotos(from, "", fotos);
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
                        enviarMensaje(from, "✅ Recibimos tus datos de envío.");
                        enviarResumenPedido(from);
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
                        Pasó un rato desde que empezamos con tus datos de envío. Tomémoslos de nuevo.""");
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
        conv.fotoWaMessageId = siguiente.waMessageId;
        if (siguiente.mediaId != null) {
            conv.paso = PEDIDO_CONFIRMAR_FOTO;
            // Se cita la foto de esta prenda (si tenemos su wa_message_id) para que quede
            // claro a cuál de las fotos en cola se refiere, cuando el cliente mandó varias.
            enviarBotonesFoto(from, "📸 Sigamos con la siguiente foto. ¿Qué hacemos con ella?", siguiente.waMessageId);
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
                // Lo que escriba el cliente acá casi siempre es un detalle más del problema ("era
                // la blusa negra", "la pedí talla M"): se agrega a la descripción del reporte en
                // vez de perderse. Si es el mismo texto que ya abrió el reporte (llegó justo
                // antes que la foto y se procesó después), no se repite nada.
                var detalle = contenido.trim();
                if (conv.descripcion != null && conv.descripcion.contains(detalle)) {
                    return false;
                }
                agregarADescripcion(conv, detalle);
                var mensajeRecordatorio = conv.paso == INC_FOTOS
                        ? "✍️ Lo anoté en tu reporte. Envíame la foto del problema, o toca \"Listo\" si prefieres continuar sin foto."
                        : "✍️ Lo anoté en tu reporte. ¿Tienes otra foto del problema, o ya está listo?";
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
                // Si hay fotos esperando respuesta (ej. el cliente mandó la foto y enseguida
                // escribió "no me llegó esta prenda"), esas fotos son del problema: pasan al
                // reporte en vez de perderse o terminar registradas como pago/prenda.
                var fotos = fotosDelPedidoEnCurso(conv);
                stateStore.remove(from);
                if (!fotos.isEmpty()) {
                    log.info("[TEXTO] Flujo PEDIDO con {} foto(s) sin clasificar + intención de inconveniente: las fotos pasan al reporte", fotos.size());
                    iniciarInconvenienteConFotos(from, contenido, fotos);
                    return false;
                }
                log.info("[TEXTO] Flujo PEDIDO activo pero detectada intención de inconveniente, eliminando conversación vieja");
                return procesarTextoEntrante(from, contenido, false);
            }
            if (conv.paso == PEDIDO_CONFIRMAR_FOTO) {
                // El cliente escribió en vez de tocar un botón. Antes esto no tenía ningún caso
                // y el bot se quedaba callado.
                if (intentDetector.esRespuestaFotoPago(contenido)) {
                    log.info("[TEXTO] Respuesta escrita a la foto interpretada como pago");
                    conv.soportePago = true;
                    registrarAbonoDesdeBot(from, conv, null);
                } else if (intentDetector.esRespuestaFotoBaul(contenido)) {
                    log.info("[TEXTO] Respuesta escrita a la foto interpretada como guardar en baúl");
                    registrarCargoDesdeBot(from, conv, null);
                } else {
                    enviarBotonesFoto(from, "No te entendí bien 🙈 Toca una opción para esta foto 👇", conv.fotoWaMessageId);
                }
                return false;
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
                enviarMensaje(from, "Un asesor ya tiene tu caso y te atiende " + horario.cuandoTeAtendemos() + ". Gracias por esperar 💜");
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

                if (texto.contains("[HABLAR_ASESOR]")) {
                    log.info("[IA] El cliente pidió hablar con un asesor");
                    pasarAAsesor(from);
                    return true;
                }

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
     * Qué hacer con una foto que llega por WhatsApp. No se le pide a ninguna IA que adivine si
     * es una prenda, un comprobante o una foto de daño (se probó con Gemini y Groq y no fue
     * confiable). Todo se decide por reglas fijas, en este orden:
     *
     * 1. Si hay un reporte de inconveniente abierto (en cualquier paso), la foto va al reporte.
     * 2. Si el texto de la foto, o lo último que escribió el cliente antes de mandarla, habla de
     *    un problema ("no me llegó esta prenda", "llegó rota"...), se abre el reporte con la
     *    foto — antes esto caía en "Guardar en baúl / Registrar pago" y terminaba como pago.
     * 3. Si el texto de la foto dice claramente que es un comprobante, se registra el abono.
     * 4. Si no, se le pregunta al cliente con botones: baúl, pago o problema.
     *
     * En ningún caso se pregunta el valor: lo completa el operador desde el panel de Cuentas.
     */
    private void procesarImagenConIA(String from, String mediaId, String mimeType, String caption, String waId) {
        // Sin caption, procesarWebhook pasa "[image]" como contenido.
        var textoFoto = caption == null || caption.equals("[image]") ? "" : caption.trim();

        // El caption (ej. "¿cuánto vale esto?") no pasa por procesarTextoEntrante, así que
        // la intención de precio se revisa acá también, sin frenar el procesamiento de la foto.
        if (intentDetector.esIntencionPrecio(textoFoto)) {
            log.info("[FOTO] Imagen con caption de intención de precio detectada, escalando a asesor");
            responderEscalarPrecio(from);
        }

        var convActiva = stateStore.get(from);
        if (convActiva != null) {
            // Mismo vencimiento que procesarTextoEntrante: antes una foto nueva podía quedar
            // "en cola" detrás de una conversación abandonada hace horas.
            var ultima = stateStore.getUltimaInteraccion(from);
            if (ultima == null || ChronoUnit.MINUTES.between(ultima, Instant.now()) >= 30) {
                log.info("[FOTO] Conversación expirada (>30 min), se descarta antes de procesar la foto");
                stateStore.remove(from);
                convActiva = null;
            }
        }

        if (convActiva != null && FLUJO_INCONVENIENTE.equals(convActiva.flujo)) {
            log.info("[FOTO] Imagen en flujo INCONVENIENTE (paso {}), se agrega al reporte", convActiva.paso);
            if (!textoFoto.isBlank()) agregarADescripcion(convActiva, textoFoto);
            if (convActiva.paso == INC_DESCRIPCION && (convActiva.descripcion == null || convActiva.descripcion.isBlank())) {
                // Todavía no sabemos qué pasó: se guarda la foto y se sigue esperando el texto.
                if (convActiva.fotosInconveniente == null) convActiva.fotosInconveniente = new java.util.ArrayList<>();
                var path = descargarMediaLocal(mediaId);
                if (path != null) convActiva.fotosInconveniente.add(path);
                enviarMensaje(from, path != null
                    ? "📸 Foto guardada en tu reporte. Ahora cuéntame qué pasó con tu pedido 📝"
                    : "⚠️ Esa foto no se pudo guardar, ¿puedes enviarla de nuevo? Y cuéntame qué pasó con tu pedido 📝");
                return;
            }
            recibirFotoInconveniente(from, convActiva, mediaId, mimeType);
            return;
        }

        var textoProblema = intentDetector.esIntencionInconveniente(textoFoto) ? textoFoto
                : convActiva == null ? textoPrevioDeInconveniente(from, waId) : null;
        if (textoProblema != null) {
            log.info("[FOTO] Foto acompañada de un reporte de problema ('{}'), se abre INCONVENIENTE con la foto", textoProblema);
            var fotos = new java.util.ArrayList<String>();
            if (convActiva != null && FLUJO_PEDIDO.equals(convActiva.flujo)) {
                fotos.addAll(fotosDelPedidoEnCurso(convActiva));
            }
            var path = descargarMediaLocal(mediaId);
            if (path != null) fotos.add(path);
            stateStore.remove(from);
            iniciarInconvenienteConFotos(from, textoProblema, fotos);
            return;
        }

        if (intentDetector.esIntencionComprobantePago(textoFoto)) {
            // No toca el estado de la conversación: si el cliente estaba en otro flujo (ej. dando
            // sus datos de envío), puede seguir donde iba.
            log.info("[FOTO] Caption de comprobante de pago, se registra el abono sin preguntar");
            var resp = cuentaService.registrarAbonoDesdeBot(from, null, mediaId, descargarMediaLocal(mediaId), mimeType);
            confirmarAbonoFoto(from, resp.id(), waId);
            return;
        }

        iniciarFlujoPedidoFoto(from, mediaId, mimeType, waId);
    }

    /**
     * Si el mensaje entrante inmediatamente anterior a esta foto fue un texto reciente
     * reportando un problema, devuelve ese texto. Cubre el caso "Hola, no me llegó esta prenda"
     * + foto cuando el texto no alcanzó a abrir el reporte (ej. era el primer mensaje del
     * número, que solo recibe el saludo de bienvenida).
     */
    private String textoPrevioDeInconveniente(String from, String waIdFoto) {
        try {
            var previo = mensajeRepo.findTop3ByWhatsappFromAndDireccionOrderByCreatedAtDesc(from, "ENTRADA").stream()
                    .filter(m -> !waIdFoto.equals(m.getWaMessageId()))
                    .findFirst()
                    .orElse(null);
            if (previo == null || !"text".equals(previo.getTipo()) || previo.getCreatedAt() == null) return null;
            if (ChronoUnit.MINUTES.between(previo.getCreatedAt(), Instant.now()) > MINUTOS_CONTEXTO_FOTO) return null;
            return intentDetector.esIntencionInconveniente(previo.getContenido()) ? previo.getContenido() : null;
        } catch (Exception e) {
            log.warn("[FOTO] No se pudo revisar el texto previo a la foto de {}: {}", from, e.getMessage());
            return null;
        }
    }

    /**
     * Fotos de un flujo PEDIDO que todavía no se clasificaron (la que espera botón + las que
     * están en cola), descargadas a disco para poder pasarlas a un reporte de inconveniente.
     */
    private List<String> fotosDelPedidoEnCurso(ConversacionCliente conv) {
        var fotos = new java.util.ArrayList<String>();
        if (conv.paso == PEDIDO_CONFIRMAR_FOTO && conv.mediaId != null) {
            var path = conv.mediaPath != null ? conv.mediaPath : descargarMediaLocal(conv.mediaId);
            if (path != null) fotos.add(path);
        }
        if (conv.prendasPendientes != null) {
            for (var p : conv.prendasPendientes) {
                if (p.mediaId == null) continue;
                var path = descargarMediaLocal(p.mediaId);
                if (path != null) fotos.add(path);
            }
        }
        return fotos;
    }

    /**
     * Abre un reporte de inconveniente que ya trae fotos. Si ya sabemos qué pasó (descripción),
     * solo se pregunta si hay más fotos; si no, se pide que cuente qué pasó.
     */
    private void iniciarInconvenienteConFotos(String from, String descripcion, List<String> fotos) {
        if (fotos.isEmpty()) {
            iniciarFlujoInconveniente(from, descripcion);
            return;
        }
        var conv = new ConversacionCliente();
        conv.flujo = FLUJO_INCONVENIENTE;
        conv.descripcion = descripcion != null ? descripcion : "";
        conv.fotosInconveniente = new java.util.ArrayList<>(fotos);
        stateStore.put(from, conv);
        var queFotos = fotos.size() == 1 ? "la foto" : "las " + fotos.size() + " fotos";
        if (conv.descripcion.isBlank()) {
            conv.paso = INC_DESCRIPCION;
            enviarMensaje(from, """
                Lamento mucho eso 😔 Ya guardé %s en tu reporte.

                Cuéntame qué pasó con tu pedido, por ejemplo: "no me llegó esta prenda" o "llegó con una mancha". 📝"""
                .formatted(queFotos));
        } else {
            conv.paso = INC_CONFIRMAR;
            enviarBotones(from, "Lamento mucho eso 😔 Ya abrí tu reporte con %s. ¿Tienes más fotos del problema?".formatted(queFotos),
                List.of(
                    Map.of("id", "inconveniente_mas_fotos", "title", "📸 Sí, tengo más"),
                    Map.of("id", "inconveniente_listo", "title", "✅ Listo, guardar")
                ));
        }
        log.info("Iniciado flujo inconveniente para {} con {} foto(s) y descripción: {}", from, fotos.size(), descripcion);
    }

    private static void agregarADescripcion(ConversacionCliente conv, String texto) {
        if (texto == null || texto.isBlank()) return;
        conv.descripcion = conv.descripcion == null || conv.descripcion.isBlank()
                ? texto.trim()
                : conv.descripcion + "\n" + texto.trim();
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

                    if (texto.contains("[HABLAR_ASESOR]")) {
                        pasarAAsesor(from);
                        return;
                    }
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
            var media = cloud.descargarMedia(mediaId);
            return media == null ? null : media.bytes();
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
                ✅ Recibimos tus datos de envío:
                • Nombre: %s
                • Teléfono: %s
                • Cédula: %s
                • Dirección: %s
                • Ciudad: %s
                • Barrio: %s""".formatted(
                datos.getNombreCompleto(), datos.getTelefono(), datos.getCedula(),
                datos.getDireccion(), datos.getCiudad(), datos.getBarrio()));
            enviarResumenPedido(from);
            log.info("Flujo envío [{}] completado en un solo mensaje", from);
            return;
        }

        switch (conv.paso) {
            case 0 -> {
                conv.nombre = contenido;
                conv.paso = 1;
                enviarMensaje(from, "¿Tu teléfono?");
            }
            case 1 -> {
                conv.telefono = contenido;
                conv.paso = 2;
                enviarMensaje(from, "¿Tu número de cédula?");
            }
            case 2 -> {
                conv.cedula = contenido;
                conv.paso = 3;
                enviarMensaje(from, "¿Tu dirección?");
            }
            case 3 -> {
                conv.direccion = contenido;
                conv.paso = 4;
                enviarMensaje(from, "¿Ciudad?");
            }
            case 4 -> {
                conv.ciudad = contenido;
                conv.paso = 5;
                enviarMensaje(from, "¿Barrio?");
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
            ¿Están bien tus datos?

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
        enviarMensaje(from, "El precio te lo confirma un asesor " + horario.cuandoTeAtendemos() + " 💜");
    }

    // Único lugar donde vive el número de pago: si algún día cambia, se edita solo acá.
    private static final String NUMERO_PAGO = "3229063035";

    private void responderComoPagar(String from) {
        enviarMensaje(from, """
            Pagas por *Nequi* o *Daviplata* al *%s*.
            Luego envíanos la foto del comprobante 💜""".formatted(NUMERO_PAGO));
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
            El día y la hora de cada live los avisamos en nuestro grupo:
            %s""".formatted(LINK_GRUPO_WHATSAPP));
        var cliente = clienteRepo.findByWhatsapp(from).orElse(null);
        if (cliente == null || !Boolean.TRUE.equals(cliente.getAvisosLive())) {
            // El link del grupo va en un mensaje aparte: en un mensaje con botones no queda clicable.
            enviarBotones(from, "¿Quieres que te avisemos por aquí cuando haya live?",
                List.of(
                    Map.of("id", "lives_si", "title", "🔔 Sí, avisarme"),
                    Map.of("id", "lives_no", "title", "No, gracias")
                ));
        }
        log.info("[LIVE] Cliente {} preguntó por el horario del live, se invitó al grupo de WhatsApp", from);
    }

    // Formato colombiano fijo ($45.000): con %,d dependía del idioma del servidor y en el
    // contenedor salía $45,000.
    private static String pesos(long valor) {
        return "$" + java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("es-CO")).format(valor);
    }

    private void cambiarAvisosLive(String from, boolean activar) {
        var cliente = clienteRepo.findByWhatsapp(from)
                .orElseGet(() -> clienteRepo.save(Cliente.builder().whatsapp(from).build()));
        cliente.setAvisosLive(activar);
        clienteRepo.save(cliente);
        log.info("[LIVE] {} {} los avisos de lives", from, activar ? "activó" : "desactivó");
    }

    /**
     * Antes de despachar, le muestra al cliente qué prendas van en su pedido y cuánto debe, para
     * que avise si falta algo ANTES de que salga (y no con un reclamo cuando ya le llegó).
     * Las prendas son los cargos desde su último envío despachado; el saldo es el total de su
     * cuenta. Si no tiene cuenta o no hay prendas nuevas, no manda nada.
     */
    private void enviarResumenPedido(String from) {
        try {
            var cuenta = cuentaRepo.findByCliente_Whatsapp(from).orElse(null);
            if (cuenta == null) return;
            var desde = solicitudRepo.findFirstByWhatsappAndEstadoOrderByCreatedAtDesc(from, "ENVIADO")
                    .map(SolicitudEnvio::getCreatedAt).orElse(Instant.EPOCH);
            var prendas = cuentaMovimientoRepo.findByCuentaIdOrderByCreatedAtDesc(cuenta.getId()).stream()
                    .filter(m -> "CARGO".equals(m.getTipo()))
                    .filter(m -> m.getCreatedAt() == null || m.getCreatedAt().isAfter(desde))
                    .toList();
            if (prendas.isEmpty()) return;

            var lineas = new StringBuilder();
            for (var m : prendas.reversed()) {
                var concepto = m.getConcepto() == null || m.getConcepto().isBlank() ? "Prenda" : m.getConcepto();
                lineas.append("• ").append(concepto).append(" — ")
                        .append(m.getValor() == null ? "valor por confirmar" : pesos(m.getValor()))
                        .append('\n');
            }
            var cargos = cuentaMovimientoRepo.sumCargos(cuenta.getId());
            var abonos = cuentaMovimientoRepo.sumAbonos(cuenta.getId());
            long saldo = (cargos != null ? cargos : 0L) - (abonos != null ? abonos : 0L);
            var porValidar = cuentaMovimientoRepo.countAbonosPorValidar(cuenta.getId());

            var texto = new StringBuilder("📋 *Resumen de tu pedido*\n").append(lineas)
                    .append("\nSaldo pendiente: *" + pesos(Math.max(saldo, 0)) + "*");
            if (porValidar > 0) texto.append("\n(Tienes %d pago(s) en revisión)".formatted(porValidar));
            texto.append("\n\n¿Está todo bien?");

            enviarBotones(from, texto.toString(),
                List.of(
                    Map.of("id", "resumen_ok", "title", "✅ Todo bien"),
                    Map.of("id", "resumen_revisar", "title", "❓ Algo no cuadra")
                ));
        } catch (Exception e) {
            // El resumen es una ayuda: si falla, los datos de envío ya quedaron guardados.
            log.warn("[ENVIO] No se pudo armar el resumen del pedido de {}: {}", from, e.getMessage());
        }
    }

    // Único lugar donde viven los días de despacho: si algún día cambian, se edita solo acá.
    // No incluye ciudad de origen ni hora exacta porque eso no está confirmado — mejor no decir
    // nada que inventar un dato (fue justo el bug que corrigió este método: la IA respondía
    // "martes y viernes desde Bogotá" y el "desde Bogotá" era inventado).
    private void responderHorarioEnvios(String from) {
        enviarMensaje(from, """
            Despachamos los *martes y viernes*. Te avisamos cuando salga tu pedido 📦""");
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
                enviarMensaje(from, "Tu saldo pendiente es *" + pesos(saldo) + "*. Un asesor lo revisa y te confirma.");
            } else if (saldo < 0) {
                enviarMensaje(from, "Tienes un saldo a favor de *" + pesos(Math.abs(saldo)) + "*. Un asesor lo revisa y te confirma.");
            } else {
                enviarMensaje(from, "No tienes saldo pendiente, estás al día ✅");
            }
        } else {
            enviarMensaje(from, "Un asesor revisa tu cuenta y te confirma el saldo " + horario.cuandoTeAtendemos() + ".");
        }
        log.info("[SALDO] Consulta de saldo desde {}, requiereAsesor=true", from);
    }

    private void iniciarFlujoPedidoFoto(String from, String mediaId, String mimeType, String waId) {
        var convExistente = stateStore.get(from);
        if (convExistente != null && FLUJO_PEDIDO.equals(convExistente.flujo)
                && convExistente.paso != PEDIDO_CONFIRMAR_FOTO && convExistente.paso != PEDIDO_VALOR_TEXTO) {
            // La prenda anterior ya quedó registrada (se le preguntaba por el envío) o todavía
            // no había ninguna: se pregunta por esta foto en la misma conversación. Antes se
            // encolaba también en este caso, y como en ese paso nadie saca prendas de la cola,
            // la foto se perdía al tocar "Solo apartar".
            convExistente.paso = PEDIDO_CONFIRMAR_FOTO;
            convExistente.mediaId = mediaId;
            convExistente.mimeType = mimeType;
            convExistente.mediaPath = null;
            convExistente.concepto = null;
            convExistente.soportePago = false;
            convExistente.fotoWaMessageId = waId;
            enviarBotonesFoto(from, "📸 Recibí tu foto. ¿Qué hacemos con ella?", null);
            return;
        }
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
        conv.fotoWaMessageId = waId;
        stateStore.put(from, conv);
        enviarBotonesFoto(from, "📸 Recibí tu foto. ¿Qué hacemos con ella?", null);
    }

    /**
     * La pregunta estándar sobre una foto. Los ids "si_foto" y "soporte_pago" se mantienen
     * para que sigan funcionando los botones que ya se mandaron antes de este cambio.
     */
    private void enviarBotonesFoto(String from, String texto, String citarWaMessageId) {
        enviarBotones(from, texto,
            List.of(
                Map.of("id", "si_foto",       "title", "🛍️ Guardar en baúl"),
                Map.of("id", "soporte_pago",  "title", "💳 Es un pago"),
                Map.of("id", "foto_problema", "title", "⚠️ Tengo un problema")
            ),
            citarWaMessageId);
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
            Para tu envío necesito: nombre, teléfono, cédula, dirección, ciudad y barrio.
            Empecemos: ¿tu nombre completo?""");
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
            var waMessageId = cloud.enviarMensajeYObtenerId(body);
            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(destinatario)
                    .contenido("[Formulario de datos de envío enviado]")
                    .tipo("interactive")
                    .direccion("SALIDA")
                    .waMessageId(waMessageId)
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
            enviarMensaje(from, "✅ Recibimos tus datos de envío.");
            enviarResumenPedido(from);
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
            Lamento eso 😔 ¿Qué problema tuvo tu pedido?""");
        log.info("Iniciado flujo inconveniente para {} con descripción: {}", from, descripcionInicial);
    }

    private void procesarTextoInconveniente(String from, ConversacionCliente conv, String contenido) {
        if (conv.paso == INC_DESCRIPCION) {
            // Se agrega (no se reemplaza) para no perder el primer mensaje que abrió el reporte.
            agregarADescripcion(conv, contenido);
            if (conv.fotosInconveniente != null && !conv.fotosInconveniente.isEmpty()) {
                // Las fotos ya llegaron antes (ej. tocó "⚠️ Tengo un problema" sobre una foto):
                // no se le vuelven a pedir.
                conv.paso = INC_CONFIRMAR;
                enviarBotones(from, "✍️ Gracias, ya quedó en tu reporte. ¿Tienes más fotos del problema o ya está listo?",
                    List.of(
                        Map.of("id", "inconveniente_mas_fotos", "title", "📸 Sí, tengo más"),
                        Map.of("id", "inconveniente_listo", "title", "✅ Listo, guardar")
                    ));
            } else {
                conv.paso = INC_FOTOS;
                enviarMensaje(from, """
                    Gracias. Ahora envíame fotos del problema 📷""");
            }
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
            ✅ Registramos tu reporte #%s. Lo revisamos y te contamos.""".formatted(inc.getId().toString().substring(0, 8)));
        log.info("Inconveniente creado desde {}: {} tipo=OTRO fotos={} requiereAsesor=true",
            from, inc.getId(), conv.fotosInconveniente != null ? conv.fotosInconveniente.size() : 0);
    }

    private void finalizarInconvenienteDanado(String from, ConversacionCliente conv) {
        var inc = inconvenienteService.crear(
            from, "DANO", conv.descripcion, null, conv.fotosInconveniente);
        stateStore.remove(from);
        enviarMensaje(from, """
            ✅ Registramos tu reporte #%s. Lo revisamos y te contamos.""".formatted(inc.getId().toString().substring(0, 8)));
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
        if (conv.mediaId != null) {
            // Viene de una foto: ya no se pregunta el valor (lo pone el operador en Cuentas) y se
            // ofrece deshacer por si el cliente tocó la opción equivocada.
            // (valor != null solo en conversaciones que quedaron a mitad del flujo viejo).
            var texto = valor == null
                    ? "✅ ¡Listo! Guardé %s en tu baúl 🛍️".formatted(concepto)
                    : "✅ ¡Listo! Guardé %s en tu baúl por %s 🛍️".formatted(concepto, pesos(valor));
            enviarBotones(from, texto,
                List.of(Map.of("id", "deshacer_" + resp.id(), "title", "↩️ Me equivoqué")),
                conv.fotoWaMessageId);
        } else if (valor == null) {
            enviarMensaje(from, "✅ Listo, apartamos %s sin precio (queda por definir). Cuando tengamos el valor te avisamos."
                    .formatted(concepto));
        } else {
            enviarMensaje(from, "✅ ¡Listo! Apartamos %s por %s.".formatted(concepto, pesos(valor)));
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
        var resp = cuentaService.registrarAbonoDesdeBot(from, valor, conv.mediaId, conv.mediaPath, conv.mimeType);
        if (valor != null) {
            // Solo llega acá una conversación que quedó a mitad del flujo viejo (cuando todavía
            // se preguntaba "¿Cuánto pagaste?") antes de desplegar este cambio.
            enviarMensaje(from, "💳 Registramos tu pago por %s. Lo revisamos y te confirmamos."
                    .formatted(pesos(valor)));
        } else {
            confirmarAbonoFoto(from, resp.id(), conv.fotoWaMessageId);
        }
        if (!avanzarSiguientePrendaPendiente(from, conv)) {
            stateStore.remove(from);
        }
    }

    private void confirmarAbonoFoto(String from, UUID movimientoId, String citarWaMessageId) {
        enviarBotones(from, "💳 Recibimos tu comprobante. Lo revisamos y te confirmamos el abono.",
            List.of(Map.of("id", "deshacer_" + movimientoId, "title", "↩️ Me equivoqué")),
            citarWaMessageId);
    }

    /**
     * Botón "↩️ Me equivoqué" tras guardar una foto en el baúl o como pago: borra ese registro
     * (si el operador todavía no lo revisó) y vuelve a preguntar qué hacer con la foto. Si en
     * este momento hay un reporte de inconveniente abierto, la foto pasa a ese reporte — es el
     * caso de quien registró como pago la foto de una prenda que no le llegó.
     */
    private void deshacerRegistroFoto(String from, String movimientoIdTexto) {
        UUID movimientoId;
        try {
            movimientoId = UUID.fromString(movimientoIdTexto);
        } catch (IllegalArgumentException e) {
            log.warn("[DESHACER] Id de movimiento inválido en botón de {}: {}", from, movimientoIdTexto);
            return;
        }

        var anulado = cuentaService.anularDesdeBot(movimientoId, from).orElse(null);
        if (anulado == null) {
            clienteRepo.findByWhatsapp(from).ifPresent(c -> {
                c.setRequiereAsesor(true);
                clienteRepo.save(c);
            });
            enviarMensaje(from, "Ese registro ya no lo puedo cambiar desde acá (puede que nuestro equipo ya lo haya revisado). Un asesor te ayuda a corregirlo en breve 💜");
            return;
        }
        log.info("[DESHACER] {} anuló su {} {}", from, anulado.tipo(), movimientoId);

        if (anulado.mediaId() == null) {
            enviarMensaje(from, "↩️ Listo, borré ese registro.");
            return;
        }
        var fotoWaMessageId = mensajeRepo.findFirstByMediaId(anulado.mediaId())
                .map(WaMensaje::getWaMessageId).orElse(null);

        var conv = stateStore.get(from);
        if (conv != null && FLUJO_INCONVENIENTE.equals(conv.flujo)) {
            if (conv.fotosInconveniente == null) conv.fotosInconveniente = new java.util.ArrayList<>();
            var path = anulado.mediaPath() != null ? anulado.mediaPath() : descargarMediaLocal(anulado.mediaId());
            if (path != null) conv.fotosInconveniente.add(path);
            enviarMensaje(from, "↩️ Listo, borré ese registro y agregué la foto a tu reporte.");
            return;
        }
        if (conv != null && FLUJO_PEDIDO.equals(conv.flujo)
                && (conv.paso == PEDIDO_CONFIRMAR_FOTO || conv.paso == PEDIDO_VALOR_TEXTO)) {
            // Se está preguntando por otra foto en este momento: esta vuelve a la cola, primera.
            if (conv.prendasPendientes == null) conv.prendasPendientes = new java.util.ArrayList<>();
            var pendiente = new PrendaPendiente();
            pendiente.mediaId = anulado.mediaId();
            pendiente.mimeType = anulado.mimeType();
            pendiente.waMessageId = fotoWaMessageId;
            conv.prendasPendientes.add(0, pendiente);
            enviarMensaje(from, "↩️ Listo, borré ese registro. Apenas terminemos con la foto actual te vuelvo a preguntar por esa.");
            return;
        }
        if (conv == null || !FLUJO_PEDIDO.equals(conv.flujo)) {
            // Sin flujo activo (o uno que no es de fotos, ej. datos de envío): se arranca uno
            // nuevo solo para esta foto.
            conv = new ConversacionCliente();
            conv.flujo = FLUJO_PEDIDO;
            stateStore.put(from, conv);
        }
        conv.paso = PEDIDO_CONFIRMAR_FOTO;
        conv.mediaId = anulado.mediaId();
        conv.mimeType = anulado.mimeType();
        conv.mediaPath = anulado.mediaPath();
        conv.concepto = null;
        conv.soportePago = false;
        conv.fotoWaMessageId = fotoWaMessageId;
        enviarBotonesFoto(from, "↩️ Listo, borré ese registro. Entonces, ¿qué hacemos con esta foto?", fotoWaMessageId);
    }

    private String descargarMediaLocal(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            log.warn("[MEDIA] descargarMediaLocal llamado sin mediaId (null o vacío)");
            return null;
        }
        try {
            var media = cloud.descargarMedia(mediaId);
            if (media == null) return null;
            var mime = media.mimeType() != null ? media.mimeType() : "image/jpeg";
            return guardarMediaLocal(media.bytes(), mime);
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
            var waMessageId = cloud.enviarMensajeYObtenerId(body);

            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(destinatario)
                    .contenido(texto)
                    .tipo("text")
                    .direccion("SALIDA")
                    .waMessageId(waMessageId)
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
            var waMessageId = cloud.enviarMensajeYObtenerId(body);

            var titulos = botones.stream().map(b -> b.get("title")).reduce((a, b1) -> a + " | " + b1).orElse("");
            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(destinatario)
                    .contenido(texto + "\n[" + titulos + "]")
                    .tipo("interactive")
                    .direccion("SALIDA")
                    .waMessageId(waMessageId)
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

        cloud.enviarMensajeSinEsperar(body, "plantilla de envío a " + destinatario);
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
            var waMessageId = cloud.enviarMensajeYObtenerId(body);
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
    public String enviarImagen(String destinatario, byte[] bytes, String mimeType, String caption) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("No hay contenido de imagen para enviar");
        }
        try {
            // Paso 1: subir el archivo a Meta — el id que devuelve acá es de SALIDA, no sirve
            // reusar el mediaId de un mensaje recibido (son namespaces distintos).
            var mimeFinal = mimeType == null || mimeType.isBlank() ? "image/jpeg" : mimeType;
            var multipart = new MultipartBodyBuilder();
            multipart.part("messaging_product", "whatsapp");
            multipart.part("type", mimeFinal);
            multipart.part("file", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return "imagen." + extensionSegunMime(mimeFinal);
                }
            }).contentType(MediaType.parseMediaType(mimeFinal));

            var mediaIdSalida = cloud.subirMedia(multipart.build());

            // Paso 2: mandar el mensaje de imagen referenciando ese id recién subido.
            var imageBody = new java.util.LinkedHashMap<String, Object>();
            imageBody.put("id", mediaIdSalida);
            if (caption != null && !caption.isBlank()) imageBody.put("caption", caption);

            var body = Map.of(
                "messaging_product", "whatsapp",
                "to", destinatario,
                "type", "image",
                "image", imageBody
            );

            var waMessageId = cloud.enviarMensajeYObtenerId(body);

            // Se guarda una copia local (mismo patrón que guardarMediaLocal) para que la
            // imagen reenviada quede visible de inmediato en el panel sin depender de que
            // Meta la siga sirviendo por su cuenta.
            var mediaPath = guardarMediaLocal(bytes, mimeFinal);
            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(destinatario)
                    .contenido(caption != null && !caption.isBlank() ? caption : "[image]")
                    .tipo("image")
                    .direccion("SALIDA")
                    .waMessageId(waMessageId)
                    .mediaPath(mediaPath)
                    .mimeType(mimeFinal)
                    .build());
            log.info("[MEDIA] Imagen enviada a {} (wa_message_id={})", destinatario, waMessageId);
            return waMessageId;
        } catch (Exception e) {
            log.error("[MEDIA] Error enviando imagen a {}: {}", destinatario, e.getMessage());
            throw new RuntimeException("Error enviando imagen WhatsApp: " + e.getMessage(), e);
        }
    }

    @Override
    public void reenviarImagen(UUID mensajeId, String destinatario) {
        var origen = mensajeRepo.findById(mensajeId)
                .orElseThrow(() -> new IllegalArgumentException("Mensaje no encontrado: " + mensajeId));
        if (!"image".equals(origen.getTipo())) {
            throw new IllegalArgumentException("Solo se pueden reenviar mensajes de tipo imagen");
        }

        byte[] bytes;
        if (origen.getMediaPath() != null && !origen.getMediaPath().isBlank()) {
            try {
                bytes = Files.readAllBytes(Paths.get(mediaDir, origen.getMediaPath()));
            } catch (Exception e) {
                log.warn("[MEDIA] No se pudo leer la copia local {} para reenviar, se intenta descargar de Meta: {}",
                        origen.getMediaPath(), e.getMessage());
                bytes = descargarMediaBytes(origen.getMediaId());
            }
        } else {
            bytes = descargarMediaBytes(origen.getMediaId());
        }

        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("No se pudo obtener la imagen para reenviar (puede haber expirado en Meta)");
        }

        var caption = origen.getContenido() != null && !origen.getContenido().startsWith("[") ? origen.getContenido() : null;
        enviarImagen(destinatario, bytes, origen.getMimeType(), caption);
    }

    @Override
    public void enviarAvisoEnviadoSinGuia(String destinatario, String nombre) {
        var nombreSaludo = (nombre == null || nombre.isBlank()) ? "" : " " + nombre;
        enviarMensaje(destinatario, """
            📦 ¡Tu pedido ya salió%s! Te compartimos la guía por aquí apenas la tengamos.""".formatted(nombreSaludo));
        log.info("[ENVIO] Aviso de envío (sin guía) enviado a {}", destinatario);
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
