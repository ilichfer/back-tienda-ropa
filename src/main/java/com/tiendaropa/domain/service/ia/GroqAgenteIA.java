package com.tiendaropa.domain.service.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.Optional;

@Slf4j
public class GroqAgenteIA implements AgenteIA {

    private final WebClient webClient;
    private final String model;
    private final String systemPrompt;
    private final ObjectMapper mapper = new ObjectMapper();

    // Modelo fijo para análisis de imagen, independiente de "model" (el de texto, configurable
    // por application.yml/GROQ_MODEL). Hoy ambos apuntan a qwen/qwen3.6-27b por defecto, pero se
    // dejan desacoplados a propósito: si el día de mañana cambian el modelo de texto a uno sin
    // visión, analizarImagen sigue funcionando igual. Si Groq retira este modelo, analizarImagen
    // volverá a fallar y WhatsAppServiceImpl se queda sin respaldo de imagen para Gemini (igual
    // que antes de este cambio) hasta que se actualice acá.
    private static final String MODELO_VISION = "qwen/qwen3.6-27b";

    public GroqAgenteIA(String apiKey, String model, String baseUrl, String systemPrompt) {
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public Optional<String> responder(String mensajeDelCliente, String contexto) {
        try {
            var messages = mapper.createArrayNode();

            var systemMsg = mapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);

            if (contexto != null && !contexto.isBlank()) {
                var ctxMsg = mapper.createObjectNode();
                ctxMsg.put("role", "user");
                ctxMsg.put("content", "Contexto de la conversación:\n" + contexto);
                messages.add(ctxMsg);

                var ctxResp = mapper.createObjectNode();
                ctxResp.put("role", "assistant");
                ctxResp.put("content", "Entendido, tengo el contexto. ¿En qué puedo ayudar?");
                messages.add(ctxResp);
            }

            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", mensajeDelCliente);
            messages.add(userMsg);

            var body = mapper.createObjectNode();
            body.put("model", model);
            body.set("messages", messages);
            body.put("temperature", 0.7);
            body.put("max_tokens", 500);
            // Modelos "pensantes" de Groq (familia Qwen 3.x, que es el default de GROQ_MODEL)
            // devuelven su razonamiento crudo como texto — sin esto, ese razonamiento se comía
            // todo el max_tokens y el cliente recibía la respuesta vacía o cortada a la mitad
            // (mismo bug que ya se corrigió para el análisis de imagen). "hidden" hace que Groq
            // devuelva solo la respuesta final. Si GROQ_MODEL cambia a un modelo no-Qwen que no
            // reconozca este parámetro, la API lo ignora sin romper la llamada.
            body.put("reasoning_format", "hidden");

            var response = webClient.post()
                    .uri("/openai/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = mapper.readTree(response);
            var choices = json.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                var contenido = choices.get(0).get("message").get("content").asText().trim();
                log.info("Groq IA respondió ({} chars)", contenido.length());
                return Optional.of(contenido);
            }

            log.warn("Groq IA sin choices en respuesta");
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error llamando Groq IA", e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<AnalisisImagen> analizarImagen(byte[] imagen, String mimeType, String contexto) {
        try {
            log.info("[GROQ] === Analizando imagen (respaldo) === tamaño={} bytes, mime={}, modelo={}",
                    imagen.length, mimeType, MODELO_VISION);

            var imagenBase64 = Base64.getEncoder().encodeToString(imagen);
            var dataUrl = "data:" + mimeType + ";base64," + imagenBase64;

            // Mismo prompt y mismo formato de respuesta que GeminiAgenteIA.analizarImagen, para
            // que WhatsAppServiceImpl pueda tratar el resultado de cualquiera de los dos agentes
            // exactamente igual.
            var prompt = """
                Analiza esta imagen y clasifícala en UNA de estas categorías:
                - PRENDA: Si es una foto de ropa, prenda de vestir, zapatos, accesorios en buen estado
                - COMPROBANTE: Si es un comprobante de pago, transferencia bancaria, screenshot de pago, PSE, Nequi, Daviplata
                - DANO: Si es una foto de ropa dañada, rota, con manchas, imperfecta, o en mal estado
                - OTRO: Si no es ninguna de las anteriores

                Responde EXACTAMENTE en este formato (sin texto adicional):
                CATEGORIA: [PRENDA|COMPROBANTE|DANO|OTRO]
                RESPUESTA: [mensaje para el cliente]
                """;

            var contentParts = mapper.createArrayNode();
            contentParts.addObject().put("type", "text").put("text", prompt);
            var imagePart = mapper.createObjectNode();
            imagePart.put("type", "image_url");
            imagePart.putObject("image_url").put("url", dataUrl);
            contentParts.add(imagePart);

            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.set("content", contentParts);

            var messages = mapper.createArrayNode();
            messages.add(userMsg);

            var body = mapper.createObjectNode();
            body.put("model", MODELO_VISION);
            body.set("messages", messages);
            body.put("temperature", 0.7);
            body.put("max_tokens", 600);
            // Sin esto, qwen/qwen3.6-27b devuelve su razonamiento <think>...</think> como
            // contenido y nunca llega a la línea CATEGORIA/RESPUESTA (bug reportado en
            // producción) — "hidden" hace que Groq devuelva solo la respuesta final. Este
            // método hoy no se llama desde el flujo principal (WhatsAppServiceImpl usa botones
            // para clasificar la foto), pero se deja corregido por si se retoma más adelante.
            body.put("reasoning_format", "hidden");

            var response = webClient.post()
                    .uri("/openai/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = mapper.readTree(response);
            var choices = json.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                log.warn("[GROQ] Sin choices al analizar imagen: {}", response);
                return Optional.empty();
            }

            var texto = choices.get(0).get("message").get("content").asText().trim();
            log.info("[GROQ] Respuesta análisis: {}", texto);

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

            log.info("[GROQ] Imagen clasificada como: {} respuesta='{}'", tipo, respuesta);
            return Optional.of(new AnalisisImagen(tipo, respuesta));
        } catch (Exception e) {
            log.error("[GROQ] ❌ Error analizando imagen", e);
            return Optional.empty();
        }
    }

    @Override
    public String nombre() {
        return "Groq-" + model;
    }
}
