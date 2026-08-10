package com.tiendaropa.domain.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public interface WhatsAppService {

    void procesarWebhook(JsonNode payload);

    void enviarMensaje(String destinatario, String texto);

    void enviarBotones(String destinatario, String texto, List<Map<String, String>> botones);

    void enviarNotificacionEnvio(String destinatario, String nombre, String guia);

    void enviarConfirmacionApartado(String destinatario, String nombre, String prenda, String precio);

    void actualizarNombreCliente(String whatsappFrom, String nombre);
}
