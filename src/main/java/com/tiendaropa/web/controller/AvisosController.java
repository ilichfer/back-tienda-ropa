package com.tiendaropa.web.controller;

import com.tiendaropa.domain.model.WaChatConservado;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.WaChatConservadoRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que el panel necesita para avisarle al equipo sin tener que estar mirando el chat:
 * quién espera a un asesor (y desde cuándo), el último mensaje de un cliente y los chats
 * antiguos pendientes de revisar. El panel lo consulta cada pocos segundos, así que son
 * consultas livianas en vez de traer todos los mensajes.
 */
@RestController
@RequestMapping("/api/avisos")
@RequiredArgsConstructor
@Slf4j
public class AvisosController {

    private final ClienteRepository clienteRepo;
    private final WaMensajeRepository mensajeRepo;
    private final WaChatConservadoRepository conservadoRepo;

    // Mes y medio sin mensajes: a partir de ahí el chat sale en "Chats antiguos".
    @Value("${chats-antiguos.dias:45}")
    private long diasChatAntiguo;

    record EsperandoAsesor(String whatsappFrom, String nombre, Instant desde) {}

    record UltimoEntrante(String id, String whatsappFrom, String nombre, String contenido, Instant createdAt) {}

    record Resumen(List<EsperandoAsesor> esperandoAsesor, UltimoEntrante ultimoEntrante,
                   long noLeidos, int chatsAntiguos) {}

    record ChatAntiguo(String whatsappFrom, String nombre, Instant ultimoMensaje, long totalMensajes) {}

    @GetMapping
    public Resumen resumen() {
        var esperando = clienteRepo.esperandoAsesor().stream()
                .map(c -> new EsperandoAsesor(c.getWhatsapp(), c.getNombre(), c.getAsesorSolicitadoEn()))
                .toList();
        var ultimo = mensajeRepo.findFirstByDireccionOrderByCreatedAtDesc("ENTRADA")
                .map(m -> new UltimoEntrante(m.getId().toString(), m.getWhatsappFrom(),
                        m.getCliente() != null ? m.getCliente().getNombre() : null,
                        m.getContenido(), m.getCreatedAt()))
                .orElse(null);
        return new Resumen(esperando, ultimo, mensajeRepo.countByDireccionAndLeidoFalse("ENTRADA"),
                chatsAntiguos().size());
    }

    /** Chats sin mensajes hace más de mes y medio que nadie ha revisado (o conservado) en ese plazo. */
    @GetMapping("/chats-antiguos")
    public List<ChatAntiguo> chatsAntiguos() {
        var limite = Instant.now().minus(diasChatAntiguo, ChronoUnit.DAYS);
        var conservados = new LinkedHashMap<String, Instant>();
        conservadoRepo.findAll().forEach(c -> conservados.put(c.getWhatsappFrom(), c.getConservadoEn()));
        return mensajeRepo.chatsSinActividadDesde(limite).stream()
                .map(fila -> {
                    var from = (String) fila[0];
                    var ultimo = aInstant(fila[1]);
                    var total = ((Number) fila[2]).longValue();
                    var nombre = clienteRepo.findByWhatsapp(from).map(c -> c.getNombre()).orElse(null);
                    return new ChatAntiguo(from, nombre, ultimo, total);
                })
                // Si lo conservaron hace menos del plazo, no se vuelve a preguntar todavía.
                .filter(c -> {
                    var conservado = conservados.get(c.whatsappFrom());
                    return conservado == null || conservado.isBefore(limite);
                })
                .toList();
    }

    @PostMapping("/chats-antiguos/conservar")
    public void conservar(@RequestBody Map<String, String> body) {
        var from = body.get("whatsappFrom");
        if (from == null || from.isBlank()) throw new IllegalArgumentException("'whatsappFrom' es requerido");
        conservadoRepo.save(WaChatConservado.builder().whatsappFrom(from).conservadoEn(Instant.now()).build());
        log.info("[CHATS-ANTIGUOS] {} se conserva", from);
    }

    private static Instant aInstant(Object valor) {
        if (valor instanceof Instant i) return i;
        if (valor instanceof java.sql.Timestamp t) return t.toInstant();
        if (valor instanceof java.time.OffsetDateTime o) return o.toInstant();
        return null;
    }
}
