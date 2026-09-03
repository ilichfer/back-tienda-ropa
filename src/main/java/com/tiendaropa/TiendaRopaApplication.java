package com.tiendaropa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// @EnableAsync habilita que WhatsAppServiceImpl.procesarWebhook corra en segundo plano
// (ver @Async ahí): así el webhook le responde "recibido" a Meta de inmediato en vez de
// esperar a que termine de bajar la imagen y analizarla con la IA, que es lo que estaba
// causando que Meta reenviara el mismo mensaje (por demora) y saliera duplicado en los logs.
@EnableAsync
@SpringBootApplication
public class TiendaRopaApplication {
    public static void main(String[] args) {
        SpringApplication.run(TiendaRopaApplication.class, args);
    }
}
