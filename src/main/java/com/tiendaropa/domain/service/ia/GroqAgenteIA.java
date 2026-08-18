package com.tiendaropa.domain.service.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

@Slf4j
public class GroqAgenteIA implements AgenteIA {

    private final WebClient webClient;
    private final String model;
    private final String systemPrompt;
    private final ObjectMapper mapper = new ObjectMapper();

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
    public String nombre() {
        return "Groq-" + model;
    }
}
