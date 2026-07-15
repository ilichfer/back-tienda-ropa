package com.tiendaropa.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaropa.domain.model.WaMensaje;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

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

    @Override
    public void procesarWebhook(JsonNode payload) {
        try {
            var entry    = payload.get("entry").get(0);
            var change   = entry.get("changes").get(0).get("value");
            var messages = change.get("messages");

            if (messages == null || messages.isEmpty()) return;

            var msg       = messages.get(0);
            var from      = msg.get("from").asText();
            var body      = msg.get("text").get("body").asText();
            var waId      = msg.get("id").asText();

            log.info("WA entrante [{}]: {}", from, body);

            var cliente = clienteRepo.findByWhatsapp(from).orElse(null);
            mensajeRepo.save(WaMensaje.builder()
                    .whatsappFrom(from)
                    .cliente(cliente)
                    .contenido(body)
                    .tipo("text")
                    .direccion("ENTRADA")
                    .waMessageId(waId)
                    .build());

            if (cliente == null) {
                enviarMensaje(from, """
                    ¡Hola! 👗 Bienvenida a nuestra tienda.

                    Para apartar una prenda necesito:
                    1. Nombre completo
                    2. Ciudad
                    3. Dirección de envío

                    ¿Cuál prenda te interesa?""");
            }

        } catch (Exception e) {
            log.error("Error procesando webhook WA", e);
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

        whatsappWebClient.post()
            .uri("/{phoneId}/messages", phoneNumberId)
            .header("Authorization", "Bearer " + accessToken)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .doOnSuccess(r -> {
                mensajeRepo.save(WaMensaje.builder()
                        .whatsappFrom(destinatario)
                        .contenido(texto)
                        .tipo("text")
                        .direccion("SALIDA")
                        .waMessageId(r.get("messages").get(0).get("id").asText())
                        .build());
            })
            .doOnError(e -> log.error("Error enviando WA a {}", destinatario, e))
            .subscribe();
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
}
