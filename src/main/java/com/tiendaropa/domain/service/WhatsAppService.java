package com.tiendaropa.domain.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface WhatsAppService {

    void procesarWebhook(JsonNode payload);

    void enviarMensaje(String destinatario, String texto);

    void enviarNotificacionEnvio(String destinatario, String nombre, String guia);

    void enviarConfirmacionApartado(String destinatario, String nombre, String prenda, String precio);
}
