package com.tiendaropa.domain.service.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;

/**
 * Único punto de contacto con la Cloud API de WhatsApp (Graph API de Meta). Antes la URL con la
 * versión ("v19.0"), el token y el phone-number-id estaban repetidos en cada servicio y
 * controller; cuando Meta retiró v19.0 (21 de mayo de 2026) había que buscarla en varios lados.
 * Ahora la versión sale de whatsapp.graph-api-version y se cambia sin tocar código.
 */
@Component
@Slf4j
public class WhatsAppCloudClient {

    // El WebClient trae por defecto un límite de 256 KB en memoria: una foto normal del celular
    // lo supera y la descarga fallaba con DataBufferLimitException. 20 MB cubre imágenes y PDFs.
    private static final int MAX_EN_MEMORIA = 20 * 1024 * 1024;

    private final String graphBaseUrl;
    private final String phoneNumberId;
    private final String accessToken;
    private final WebClient webClient;

    public WhatsAppCloudClient(
            @Value("${whatsapp.graph-api-version:v25.0}") String graphApiVersion,
            @Value("${whatsapp.phone-number-id}") String phoneNumberId,
            @Value("${whatsapp.access-token}") String accessToken) {
        this.graphBaseUrl = "https://graph.facebook.com/" + graphApiVersion;
        this.phoneNumberId = phoneNumberId;
        this.accessToken = accessToken;
        this.webClient = WebClient.builder()
                .baseUrl(graphBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_EN_MEMORIA))
                // Sin esto una llamada colgada a Meta dejaba el hilo del webhook esperando para siempre.
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                        .responseTimeout(Duration.ofSeconds(30))))
                .build();
        log.info("[WA-CLOUD] Graph API {} para el número {}", graphApiVersion, phoneNumberId);
    }

    /** POST /{phone-number-id}/messages: envía cualquier tipo de mensaje y devuelve la respuesta de Meta. */
    public JsonNode enviarMensaje(Map<String, ?> body) {
        return webClient.post()
                .uri("/{phoneId}/messages", phoneNumberId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }

    /** Igual que {@link #enviarMensaje} pero devuelve solo el wa_message_id del mensaje enviado. */
    public String enviarMensajeYObtenerId(Map<String, ?> body) {
        return idDelMensaje(enviarMensaje(body));
    }

    /** Envío sin esperar respuesta (solo deja el resultado en el log). */
    public void enviarMensajeSinEsperar(Map<String, ?> body, String descripcion) {
        webClient.post()
                .uri("/{phoneId}/messages", phoneNumberId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .subscribe(
                        r -> log.info("[WA-CLOUD] {} enviado", descripcion),
                        e -> log.error("[WA-CLOUD] Error enviando {}", descripcion, e));
    }

    /** POST /{phone-number-id}/media: sube un archivo y devuelve el media id de salida. */
    public String subirMedia(MultiValueMap<String, ?> multipart) {
        var r = webClient.post()
                .uri("/{phoneId}/media", phoneNumberId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        return r.get("id").asText();
    }

    /** POST /{phone-number-id}/smb_app_data (coexistencia): pide contactos o historial. */
    public JsonNode solicitarSincronizacion(String syncType) {
        return webClient.post()
                .uri("/{phoneId}/smb_app_data", phoneNumberId)
                .bodyValue(Map.of("messaging_product", "whatsapp", "sync_type", syncType))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }

    /** Descarga un media recibido. Devuelve null si Meta no da la URL de descarga. */
    public Media descargarMedia(String mediaId) {
        var meta = webClient.get()
                .uri("/{mediaId}?fields=url,mime_type", mediaId)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        var url = meta == null ? null : meta.path("url").asText(null);
        if (url == null) {
            log.warn("[WA-CLOUD] Meta no devolvió 'url' para el media {}: {}", mediaId, meta);
            return null;
        }
        // La URL de descarga es absoluta (lookaside.fbsbx.com) y también exige el token.
        var bytes = webClient.get()
                .uri(java.net.URI.create(url))
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
        return new Media(bytes, meta.path("mime_type").asText(null));
    }

    /** URL de la metadata de un media, para quien necesite hacer la descarga por su cuenta. */
    public String urlMetadataMedia(String mediaId) {
        return graphBaseUrl + "/" + mediaId + "?fields=url,mime_type";
    }

    public String accessToken() {
        return accessToken;
    }

    static String idDelMensaje(JsonNode respuesta) {
        return respuesta.get("messages").get(0).get("id").asText();
    }

    public record Media(byte[] bytes, String mimeType) {}
}
