package com.tiendaropa.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient whatsappWebClient() {
        return WebClient.builder()
                .baseUrl("https://graph.facebook.com/v19.0")
                .build();
    }
}
