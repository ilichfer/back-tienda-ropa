package com.tiendaropa.domain.service.impl;

import com.tiendaropa.domain.model.WaBroadcast;
import com.tiendaropa.domain.model.WaBroadcastEnvio;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.WaBroadcastEnvioRepository;
import com.tiendaropa.domain.repository.WaBroadcastRepository;
import com.tiendaropa.domain.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Bean aparte solo para que @Async funcione de verdad: Spring implementa @Async con un proxy,
// y el proxy NO intercepta una llamada interna tipo this.metodo() dentro de la misma clase
// (BroadcastServiceImpl.crearYEjecutar llamando a su propio método async) — tiene que ser una
// llamada externa a otro bean, como esta, para que realmente corra en otro hilo.
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastRunner {

    // Valor especial en variablesConfig: se reemplaza por el nombre del Cliente destinatario
    // (o su número si no tiene nombre guardado), en vez de mandarse igual a todos.
    private static final String TOKEN_NOMBRE_CLIENTE = "{{cliente_nombre}}";

    // Pausa entre envíos para no golpear de una el rate limit de Meta — un negocio de este
    // tamaño no necesita una cola/worker aparte, alcanza con espaciar los llamados.
    private static final long PAUSA_ENTRE_ENVIOS_MS = 250;

    private final WhatsAppService whatsAppService;
    private final ClienteRepository clienteRepo;
    private final WaBroadcastRepository broadcastRepo;
    private final WaBroadcastEnvioRepository envioRepo;

    @Async
    public void ejecutar(UUID broadcastId, String nombrePlantilla, String idioma, String etiquetasVariables,
                          Map<String, String> variablesConfig, List<String> destinatarios) {
        var etiquetas = etiquetasVariables == null || etiquetasVariables.isBlank()
                ? List.<String>of()
                : List.of(etiquetasVariables.split(","));

        var enviados = 0;
        var fallidos = 0;

        for (var whatsappFrom : destinatarios) {
            var envio = WaBroadcastEnvio.builder()
                    .broadcastId(broadcastId)
                    .whatsappFrom(whatsappFrom)
                    .estado("PENDIENTE")
                    .build();
            try {
                var valores = resolverValores(etiquetas, variablesConfig, whatsappFrom);
                var waMessageId = whatsAppService.enviarPlantillaMeta(whatsappFrom, nombrePlantilla, idioma, valores);
                envio.setEstado("ENVIADO");
                envio.setWaMessageId(waMessageId);
                enviados++;
            } catch (Exception e) {
                envio.setEstado("FALLIDO");
                envio.setError(e.getMessage());
                fallidos++;
                log.warn("[BROADCAST] Fallo enviando a {}: {}", whatsappFrom, e.getMessage());
            }
            envioRepo.save(envio);

            try {
                Thread.sleep(PAUSA_ENTRE_ENVIOS_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        var broadcast = broadcastRepo.findById(broadcastId).orElse(null);
        if (broadcast != null) {
            broadcast.setEnviados(enviados);
            broadcast.setFallidos(fallidos);
            broadcast.setEstado("COMPLETADO");
            broadcastRepo.save(broadcast);
        }
        log.info("[BROADCAST] {} terminado: {} enviados, {} fallidos de {}", broadcastId, enviados, fallidos, destinatarios.size());
    }

    private List<String> resolverValores(List<String> etiquetas, Map<String, String> variablesConfig, String whatsappFrom) {
        var valores = new ArrayList<String>();
        for (var etiqueta : etiquetas) {
            var valor = variablesConfig != null ? variablesConfig.get(etiqueta.trim()) : null;
            if (TOKEN_NOMBRE_CLIENTE.equals(valor)) {
                var cliente = clienteRepo.findByWhatsapp(whatsappFrom).orElse(null);
                valor = cliente != null && cliente.getNombre() != null && !cliente.getNombre().isBlank()
                        ? cliente.getNombre() : whatsappFrom;
            }
            valores.add(valor != null ? valor : "");
        }
        return valores;
    }
}
