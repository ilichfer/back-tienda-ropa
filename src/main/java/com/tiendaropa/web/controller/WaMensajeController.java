package com.tiendaropa.web.controller;

import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.WaMensaje;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.WhatsAppService;
import com.tiendaropa.web.dto.request.ImportarMensajeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/wa-mensajes")
@RequiredArgsConstructor
@Slf4j
public class WaMensajeController {

    private final WaMensajeRepository waMensajeRepo;
    private final ClienteRepository clienteRepo;
    private final WhatsAppService whatsAppService;

    @Value("${whatsapp.media-dir:./media}")
    private String mediaDir;

    @GetMapping
    public List<WaMensaje> listar(@RequestParam(required = false) String whatsapp) {
        if (whatsapp != null) {
            return waMensajeRepo.findByWhatsappFromConCliente(whatsapp);
        }
        return waMensajeRepo.findAllConCliente();
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

    @PostMapping("/importar")
    public Map<String, Object> importar(@RequestBody List<ImportarMensajeRequest> mensajes) {
        int creados = 0;
        int errores = 0;
        String lastError = null;

        for (var req : mensajes) {
            try {
                Cliente cliente = null;
                if (req.getWhatsappFrom() != null && !req.getWhatsappFrom().isBlank()) {
                    cliente = clienteRepo.findByWhatsapp(req.getWhatsappFrom()).orElse(null);
                    if (cliente == null && req.getNombre() != null && !req.getNombre().isBlank()) {
                        cliente = clienteRepo.save(Cliente.builder()
                                .whatsapp(req.getWhatsappFrom())
                                .nombre(req.getNombre())
                                .build());
                    }
                }

                var tipo = req.getTipo() != null ? req.getTipo() : "text";
                var direccion = req.getDireccion() != null ? req.getDireccion() : "ENTRADA";
                var contenido = req.getContenido() != null ? req.getContenido() : "";

                Instant createdAt = Instant.now();
                if (req.getTimestamp() != null && req.getTimestamp() > 0) {
                    createdAt = Instant.ofEpochMilli(req.getTimestamp());
                }

                var msg = WaMensaje.builder()
                        .whatsappFrom(req.getWhatsappFrom())
                        .cliente(cliente)
                        .contenido(contenido)
                        .tipo(tipo)
                        .direccion(direccion)
                        .mimeType(req.getMimeType())
                        .mediaPath(req.getMediaPath())
                        .build();

                waMensajeRepo.save(msg);
                creados++;
            } catch (Exception e) {
                errores++;
                lastError = e.getMessage();
                log.error("Error importando mensaje: {}", e.getMessage());
            }
        }

        return Map.of("creados", creados, "errores", errores, "lastError", lastError != null ? lastError : "");
    }

    @PostMapping(value = "/importar/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> importarMedia(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "subdir", defaultValue = "migracion") String subdir) throws IOException {

        var fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        var targetDir = Paths.get(mediaDir, subdir);
        Files.createDirectories(targetDir);
        var targetPath = targetDir.resolve(fileName);
        file.transferTo(targetPath.toFile());

        var relativePath = subdir + "/" + fileName;
        log.info("Media guardada: {} ({} bytes)", relativePath, file.getSize());

        return Map.of("path", relativePath, "originalName", file.getOriginalFilename());
    }
}
