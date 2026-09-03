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
                - PRENDA: Si es una foto de ropa, prenda de vestir, zapatos, accesorios en buen estado
                - COMPROBANTE: Si es un comprobante de pago, transferencia bancaria, screenshot de pago, PSE, Nequi, Daviplata
                - DANO: Si es una foto de ropa dañada, rota, con manchas, imperfecta, o en mal estado
                - OTRO: Si no es ninguna de las anteriores

                Responde EXACTAMENTE en este formato (sin texto adicional):
                CATEGORIA: [PRENDA|COMPROBANTE|DANO|OTRO]
                RESPUESTA: [mensaje para el cliente]
                """;
            textPart.put("text", prompt);
            userParts.add(textPart);

            userMsg.set("parts", userParts);
            contents.add(userMsg);

            var body = construirBody(contents, null);

            log.info("[GEMINI] Enviando imagen para análisis...");
            var response = llamarConReintentos(body);

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
                // Antes faltaba la rama de DANO acá: una foto que Gemini clasificaba
                // correctamente como "DANO" caía igual en el "else" de abajo y quedaba
                // marcada como "OTRO", así que resultado.esDanado() nunca daba true y la
                // foto de una prenda dañada nunca entraba al flujo de INCONVENIENTE — se
                // limitaba a mandarle al cliente el texto de resultado.respuesta() suelto.
                if (cat.contains("PRENDA")) tipo = "PRENDA";
                else if (cat.contains("COMPROBANTE")) tipo = "COMPROBANTE";
                else if (cat.contains("DANO")) tipo = "DANO";
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

    @Override
    public Optional<String> responderAudio(byte[] audio, String mimeType, String contexto) {
        try {
            log.info("[GEMINI] === Interpretando audio === tamaño={} bytes, mime={}", audio.length, mimeType);

            var audioBase64 = Base64.getEncoder().encodeToString(audio);
            var contents = mapper.createArrayNode();

            if (contexto != null && !contexto.isBlank()) {
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

            var audioPart = mapper.createObjectNode();
            var inlineData = mapper.createObjectNode();
            inlineData.put("mimeType", mimeType);
            inlineData.put("data", audioBase64);
            audioPart.set("inlineData", inlineData);
            userParts.add(audioPart);

            var textPart = mapper.createObjectNode();
            textPart.put("text", "El cliente envió esta nota de voz. Escucha lo que dice y respóndele siguiendo tus instrucciones normales (incluyendo los marcadores [SOLICITAR_ENVIO] o [REPORTE_INCONVENIENTE] si aplican).");
            userParts.add(textPart);

            userMsg.set("parts", userParts);
            contents.add(userMsg);

            var body = construirBody(contents, null);

            log.info("[GEMINI] Enviando audio para interpretar...");
            var response = llamarConReintentos(body);

            return extraerTexto(response);
        } catch (Exception e) {
            log.error("[GEMINI] ❌ Error interpretando audio", e);
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
        // gemini-2.5-flash "piensa" antes de responder y esos tokens de razonamiento salen
        // del mismo presupuesto de maxOutputTokens. Con 500 tokens, el modelo a veces gastaba
        // casi todo pensando y la respuesta visible quedaba cortada a media frase (se vio en
        // producción: una respuesta de solo 65 caracteres terminando en "...foto! Para").
        // thinkingBudget=0 apaga ese razonamiento interno para estas llamadas (clasificación/
        // respuesta corta al cliente, no necesitan razonar) y además responde más rápido.
        var thinkingConfig = mapper.createObjectNode();
        thinkingConfig.put("thinkingBudget", 0);
        genConfig.set("thinkingConfig", thinkingConfig);
        // Con el razonamiento apagado 500 ya alcanzaría, pero se sube a 1024 como margen de
        // seguridad para respuestas más largas (ej. contexto con varios mensajes previos).
        genConfig.put("maxOutputTokens", 1024);
        body.set("generationConfig", genConfig);

        return body;
    }

    private Optional<String> extraerTexto(String response) {
        try {
            JsonNode json = mapper.readTree(response);
            var candidates = json.get("candidates");
            if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
                var primerCandidato = candidates.get(0);
                var finishReason = primerCandidato.has("finishReason") ? primerCandidato.get("finishReason").asText() : "";
                if ("MAX_TOKENS".equals(finishReason)) {
                    // Señal de que la respuesta se cortó por quedarse sin presupuesto de tokens
                    // (ver el comentario de thinkingBudget en construirBody) — se deja el warn
                    // para detectar rápido si volviera a pasar.
                    log.warn("[GEMINI] ⚠️ finishReason=MAX_TOKENS, la respuesta puede venir incompleta");
                }
                var content = primerCandidato.get("content");
                if (content != null) {
                    var parts = content.get("parts");
                    if (parts != null && parts.isArray() && !parts.isEmpty()) {
                        var texto = parts.get(0).get("text").asText().trim();
                        log.info("[GEMINI] ✅ Respuesta exitosa ({} chars, finishReason={}): '{}'", texto.length(), finishReason, texto.substring(0, Math.min(150, texto.length())));
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
        // Ni el 429 (cupo agotado) ni el 503 (servicio de Google saturado/caído) son fallas que
        // un backoff largo arregle: si el servicio está caído, esperar 96s no lo revive, y
        // mientras tanto el cliente en WhatsApp no recibe ninguna respuesta (se vio en
        // producción: 5 intentos con backoff de hasta 96s c/u, ~186s en total, antes de recién
        // ahí pasar a Groq). Es mejor rendirse rápido acá y dejar que WhatsAppServiceImpl pase
        // al agente de respaldo de inmediato, si hay uno configurado.
        int maxReintentosRateLimit = 2;
        int maxReintentosServicioCaido = 2;
        for (int intento = 1; intento <= Math.max(maxReintentosRateLimit, maxReintentosServicioCaido); intento++) {
            try {
                return webClient.post()
                        .uri("/v1beta/models/{model}:generateContent?key={key}", modelo, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body.toString())
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests e) {
                if (intento >= maxReintentosRateLimit) {
                    log.warn("[GEMINI] 429 rate limit persiste tras {} intento(s), se rinde (dejando paso al agente de respaldo si hay)", intento);
                    throw e;
                }
                var espera = intento * 3L;
                log.warn("[GEMINI] 429 rate limit en intento {}/{}, esperando {}s...", intento, maxReintentosRateLimit, espera);
                Thread.sleep(espera * 1000);
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException.ServiceUnavailable e) {
                if (intento >= maxReintentosServicioCaido) {
                    log.warn("[GEMINI] 503 persiste tras {} intento(s), se rinde (dejando paso al agente de respaldo si hay)", intento);
                    throw e;
                }
                var espera = intento * 3L;
                log.warn("[GEMINI] 503 en intento {}/{}, esperando {}s...", intento, maxReintentosServicioCaido, espera);
                Thread.sleep(espera * 1000);
            }
        }
        return null;
    }

    @Override
    public String nombre() {
        return "Gemini-" + model;
    }
}
