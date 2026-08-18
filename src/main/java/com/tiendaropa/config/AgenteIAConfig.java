package com.tiendaropa.config;

import com.tiendaropa.domain.service.ia.AgenteIA;
import com.tiendaropa.domain.service.ia.GroqAgenteIA;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

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

    @Value("${agente-ia.system-prompt:}")
    private String systemPrompt;

    @Bean
    public Optional<AgenteIA> agenteIA() {
        return switch (provider.toLowerCase()) {
            case "groq" -> {
                if (groqApiKey.isBlank()) {
                    log.warn("agente-ia.provider=groq pero no se configuró agente-ia.groq.api-key. IA deshabilitada.");
                    yield Optional.empty();
                }
                var agente = new GroqAgenteIA(groqApiKey, groqModel, groqBaseUrl, systemPrompt);
                log.info("Agente IA habilitado: {} con modelo {}", agente.nombre(), groqModel);
                yield Optional.of(agente);
            }
            default -> {
                log.info("Agente IA deshabilitado (provider: {})", provider);
                yield Optional.empty();
            }
        };
    }
}
