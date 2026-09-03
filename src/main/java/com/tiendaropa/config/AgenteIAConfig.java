package com.tiendaropa.config;

import com.tiendaropa.domain.service.ia.AgenteIA;
import com.tiendaropa.domain.service.ia.GeminiAgenteIA;
import com.tiendaropa.domain.service.ia.GroqAgenteIA;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@Slf4j
public class AgenteIAConfig {

    @Value("${agente-ia.provider:ninguno}")
    private String provider;

    @Value("${agente-ia.groq.api-key:}")
    private String groqApiKey;

    @Value("${agente-ia.groq.model:qwen/qwen3.6-27b}")
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

    /**
     * Lista ordenada de agentes IA activos: el primero es el proveedor principal
     * (agente-ia.provider), el segundo —si tiene api-key configurada— es el respaldo
     * automático (failover) que WhatsAppServiceImpl usa si el principal falla, se cae o no
     * responde. Antes solo se instanciaba un único agente; con esto, si por ejemplo Gemini
     * se queda sin cuota, el bot sigue funcionando con Groq sin que el cliente lo note.
     * Si ninguno tiene api-key, o provider=ninguno, la lista queda vacía (IA deshabilitada).
     */
    @Bean
    public List<AgenteIA> agentesIA() {
        AgenteIA gemini = geminiApiKey.isBlank() ? null
                : new GeminiAgenteIA(geminiApiKey, geminiModel, geminiBaseUrl, systemPrompt);
        AgenteIA groq = groqApiKey.isBlank() ? null
                : new GroqAgenteIA(groqApiKey, groqModel, groqBaseUrl, systemPrompt);

        AgenteIA primario;
        AgenteIA respaldo;
        switch (provider.toLowerCase()) {
            case "gemini" -> { primario = gemini; respaldo = groq; }
            case "groq"   -> { primario = groq;   respaldo = gemini; }
            default       -> { primario = null;   respaldo = null; }
        }

        var agentes = new ArrayList<AgenteIA>();
        if (primario == null) {
            if ("gemini".equalsIgnoreCase(provider) || "groq".equalsIgnoreCase(provider)) {
                log.warn("agente-ia.provider={} pero no hay api-key configurada para ese proveedor. IA deshabilitada.", provider);
            } else {
                log.info("Agente IA deshabilitado (provider: {})", provider);
            }
        } else {
            agentes.add(primario);
            log.info("Agente IA principal: {}", primario.nombre());
            if (respaldo != null) {
                agentes.add(respaldo);
                log.info("Agente IA de respaldo (failover automático): {}", respaldo.nombre());
            }
        }

        log.info("=========================================");
        log.info("[CONFIG] agente-ia.provider='{}' -> {} agente(s) activo(s)", provider, agentes.size());
        log.info("=========================================");

        return agentes;
    }
}
