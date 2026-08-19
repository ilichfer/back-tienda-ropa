package com.tiendaropa.domain.service.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.Optional;

@Slf4j
public class GeminiAgenteIA implements AgenteIA {

    private final WebClient webClient;
    private final String model;
    private final String systemPrompt;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiAgenteIA(String apiKey, String model, String baseUrl, String systemPrompt) {
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.apiKey = apiKey;
    }

    private final String apiKey;

    @Override
    public Optional<String> responder(String mensajeDelCliente, String contexto) {
        try {
            log.info("[GEMINI] === Iniciando llamada API === mensaje='{}'", mensajeDelCliente);
            var contents = mapper.createArrayNode();

            if (contexto != null && !contexto.isBlank()) {
                log.info("[GEMINI] Agregando contexto ({} chars)", contexto.length());
                var ctxUser = mapper.createObjectNode();
                ctxUser.put("role", "user");
                var ctxParts = mapper.createArrayNode();
                ctxParts.addObject().put("text", "Contexto de la conversación:\n" + contexto);
                ctxUser.set("parts", ctxParts);
                contents.add(ctxUser);

                var ctxModel = mapper.createObjectNode();
                ctxModel.put("role", "model");
                var ctxModelParts = mapper.createArrayNode();
                ctxModelParts.addObject().put("text", "Entendido, tengo el contexto de la tienda. ¿En qué puedo ayudar al cliente?");
                ctxModel.set("parts", ctxModelParts);
                contents.add(ctxModel);
            }

            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            var userParts = mapper.createArrayNode();
            userParts.addObject().put("text", mensajeDelCliente);
            userMsg.set("parts", userParts);
            contents.add(userMsg);

            var body = construirBody(contents, null);

            log.info("[GEMINI] Enviando request a /v1beta/models/{}:generateContent", model);
            log.debug("[GEMINI] Body: {}", body.toString());

            var response = llamarConReintentos(body);

            log.info("[GEMINI] Response recibida ({} chars)", response != null ? response.length() : 0);
            log.debug("[GEMINI] Response body: {}", response);

            return extraerTexto(response);
        } catch (Exception e) {
            log.error("[GEMINI] ❌ Error llamando API", e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<AnalisisImagen> analizarImagen(byte[] imagen, String mimeType, String contexto) {
        try {
            log.info("[GEMINI] === Analizando imagen === tamaño={} bytes, mime={}", imagen.length, mimeType);

            var imagenBase64 = Base64.getEncoder().encodeToString(imagen);

            var contents = mapper.createArrayNode();

            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            var userParts = mapper.createArrayNode();

            var imagePart = mapper.createObjectNode();
            var inlineData = mapper.createObjectNode();
            inlineData.put("mimeType", mimeType);
            inlineData.put("data", imagenBase64);
            imagePart.set("inlineData", inlineData);
            userParts.add(imagePart);

            var textPart = mapper.createObjectNode();
            String prompt = """
                Analiza esta imagen y clasifícala en UNA de estas categorías:
                - PRENDA: Si es una foto de ropa, prenda de vestir, zapatos, accesorios
                - COMPROBANTE: Si es un comprobante de pago, transferencia bancaria, screenshot de pago, PSE, Nequi, Daviplata
                - OTRO: Si no es ninguna de las anteriores

                Responde EXACTAMENTE en este formato (sin texto adicional):
                CATEGORIA: [PRENDA|COMPROBANTE|OTRO]
                RESPUESTA: [mensaje para el cliente]
                """;
            textPart.put("text", prompt);
            userParts.add(textPart);

            userMsg.set("parts", userParts);
            contents.add(userMsg);

            var body = construirBody(contents, null);

            log.info("[GEMINI] Enviando imagen para análisis con gemini-2.5-flash-image...");
            var response = llamarConReintentos(body, "gemini-2.5-flash-image");

            var textoOpt = extraerTexto(response);
            if (textoOpt.isEmpty()) {
                log.warn("[GEMINI] No se pudo analizar la imagen");
                return Optional.empty();
            }

            var texto = textoOpt.get();
            log.info("[GEMINI] Respuesta análisis: {}", texto);

            var tipo = "OTRO";
            var respuesta = texto;

            if (texto.contains("CATEGORIA:")) {
                var lineaCat = texto.lines()
                        .filter(l -> l.trim().startsWith("CATEGORIA:"))
                        .findFirst()
                        .orElse("");
                var cat = lineaCat.replace("CATEGORIA:", "").trim().toUpperCase();
                if (cat.contains("PRENDA")) tipo = "PRENDA";
                else if (cat.contains("COMPROBANTE")) tipo = "COMPROBANTE";
                else tipo = "OTRO";

                var lineaResp = texto.lines()
                        .filter(l -> l.trim().startsWith("RESPUESTA:"))
                        .findFirst()
                        .orElse("");
                if (!lineaResp.isBlank()) {
                    respuesta = lineaResp.replace("RESPUESTA:", "").trim();
                }
            }

            log.info("[GEMINI] Imagen clasificada como: {} respuesta='{}'", tipo, respuesta);
            return Optional.of(new AnalisisImagen(tipo, respuesta));

        } catch (Exception e) {
            log.error("[GEMINI] ❌ Error analizando imagen", e);
            return Optional.empty();
        }
    }

    private JsonNode construirBody(JsonNode contents, JsonNode systemInstruction) {
        var body = mapper.createObjectNode();
        body.set("contents", contents);

        if (systemInstruction != null) {
            body.set("systemInstruction", systemInstruction);
        } else {
            var si = mapper.createObjectNode();
            var parts = mapper.createArrayNode();
            parts.addObject().put("text", systemPrompt);
            si.set("parts", parts);
            body.set("systemInstruction", si);
        }

        var genConfig = mapper.createObjectNode();
        genConfig.put("temperature", 0.7);
        genConfig.put("maxOutputTokens", 500);
        body.set("generationConfig", genConfig);

        return body;
    }

    private Optional<String> extraerTexto(String response) {
        try {
            JsonNode json = mapper.readTree(response);
            var candidates = json.get("candidates");
            if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
                var content = candidates.get(0).get("content");
                if (content != null) {
                    var parts = content.get("parts");
                    if (parts != null && parts.isArray() && !parts.isEmpty()) {
                        var texto = parts.get(0).get("text").asText().trim();
                        log.info("[GEMINI] ✅ Respuesta exitosa ({} chars): '{}'", texto.length(), texto.substring(0, Math.min(150, texto.length())));
                        return Optional.of(texto);
                    }
                }
            }
            log.warn("[GEMINI] ⚠️ Sin candidates en respuesta: {}", response);
            return Optional.empty();
        } catch (Exception e) {
            log.error("[GEMINI] Error parseando respuesta", e);
            return Optional.empty();
        }
    }

    private String llamarConReintentos(JsonNode body) throws InterruptedException {
        return llamarConReintentos(body, this.model);
    }

    private String llamarConReintentos(JsonNode body, String modelo) throws InterruptedException {
        for (int intento = 1; intento <= 3; intento++) {
            try {
                return webClient.post()
                        .uri("/v1beta/models/{model}:generateContent?key={key}", modelo, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body.toString())
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests e) {
                var espera = intento * 3L;
                log.warn("[GEMINI] 429 rate limit en intento {}/3, esperando {}s...", intento, espera);
                if (intento < 3) Thread.sleep(espera * 1000);
                else throw e;
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException.ServiceUnavailable e) {
                var espera = intento * 2L;
                log.warn("[GEMINI] 503 en intento {}/3, esperando {}s...", intento, espera);
                if (intento < 3) Thread.sleep(espera * 1000);
                else throw e;
            }
        }
        return null;
    }

    @Override
    public String nombre() {
        return "Gemini-" + model;
    }
}
