package com.tiendaropa.config;

import com.tiendaropa.domain.service.ia.AgenteIA;
import com.tiendaropa.domain.service.ia.GeminiAgenteIA;
import com.tiendaropa.domain.service.ia.GroqAgenteIA;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class AgenteIAConfig {

    @Value("${agente-ia.provider:ninguno}")
    private String provider;

    @Value("${agente-ia.groq.api-key:}")
    private String groqApiKey;

    @Value("${agente-ia.groq.model:llama-3.3-70b-versatile}")
    private String groqModel;

    @Value("${agente-ia.groq.base-url:https://api.groq.com}")
    private String groqBaseUrl;

    @Value("${agente-ia.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${agente-ia.gemini.model:gemini-2.0-flash}")
    private String geminiModel;

    @Value("${agente-ia.gemini.base-url:https://generativelanguage.googleapis.com}")
    private String geminiBaseUrl;

    @Value("${agente-ia.system-prompt:}")
    private String systemPrompt;

    @Bean
    public AgenteIA agenteIA() {
        log.info("=========================================");
        log.info("[CONFIG] agente-ia.provider='{}'", provider);
        log.info("[CONFIG] agente-ia.gemini.api-key length={}", geminiApiKey.length());
        log.info("[CONFIG] agente-ia.gemini.model='{}'", geminiModel);
        log.info("=========================================");

        return switch (provider.toLowerCase()) {
            case "groq" -> {
                if (groqApiKey.isBlank()) {
                    log.warn("agente-ia.provider=groq pero no hay api-key. IA deshabilitada.");
                    yield null;
                }
                var agente = new GroqAgenteIA(groqApiKey, groqModel, groqBaseUrl, systemPrompt);
                log.info("Agente IA habilitado: {} con modelo {}", agente.nombre(), groqModel);
                yield agente;
            }
            case "gemini" -> {
                if (geminiApiKey.isBlank()) {
                    log.warn("agente-ia.provider=gemini pero no hay api-key. IA deshabilitada.");
                    yield null;
                }
                var agente = new GeminiAgenteIA(geminiApiKey, geminiModel, geminiBaseUrl, systemPrompt);
                log.info("Agente IA habilitado: {} con modelo {}", agente.nombre(), geminiModel);
                yield agente;
            }
            default -> {
                log.info("Agente IA deshabilitado (provider: {})", provider);
                yield null;
            }
        };
    }
}
