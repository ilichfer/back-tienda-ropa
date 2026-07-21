package com.tiendaropa.web.controller;

import com.tiendaropa.domain.model.WaMensaje;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wa-mensajes")
@RequiredArgsConstructor
public class WaMensajeController {

    private final WaMensajeRepository waMensajeRepo;
    private final WhatsAppService whatsAppService;

    @GetMapping
    public List<WaMensaje> listar(@RequestParam(required = false) String whatsapp) {
        if (whatsapp != null) {
            return waMensajeRepo.findByWhatsappFromOrderByCreatedAtDesc(whatsapp);
        }
        return waMensajeRepo.findAll();
    }

    @PostMapping("/enviar")
    public void enviar(@RequestBody Map<String, String> body) {
        var to = body.get("to");
        if (to == null || to.isBlank()) throw new IllegalArgumentException("'to' es requerido");
        var texto = body.get("texto");
        if (texto == null || texto.isBlank()) throw new IllegalArgumentException("'texto' es requerido");
        whatsAppService.enviarMensaje(to, texto);
    }

    @PutMapping("/cliente")
    public void actualizarCliente(@RequestBody Map<String, String> body) {
        var whatsappFrom = body.get("whatsappFrom");
        var nombre = body.get("nombre");
        if (whatsappFrom == null || whatsappFrom.isBlank())
            throw new IllegalArgumentException("'whatsappFrom' es requerido");
        if (nombre == null || nombre.isBlank())
            throw new IllegalArgumentException("'nombre' es requerido");
        whatsAppService.actualizarNombreCliente(whatsappFrom, nombre);
    }
}
