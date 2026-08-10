package com.tiendaropa.web.controller;

import com.tiendaropa.domain.service.CuentaService;
import com.tiendaropa.web.dto.response.CuentaMovimientoResponse;
import com.tiendaropa.web.dto.response.CuentaResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/cuentas")
@RequiredArgsConstructor
@Slf4j
public class CuentaController {

    private final CuentaService cuentaService;

    @Value("${whatsapp.media-dir:./media}")
    private String mediaDir;

    @GetMapping
    public List<CuentaResponse> listar() {
        return cuentaService.listar();
    }

    @PostMapping
    public CuentaResponse crearCuenta(@RequestBody Map<String, String> body) {
        var whatsapp = body.get("whatsapp");
        if (whatsapp == null || whatsapp.isBlank())
            throw new IllegalArgumentException("'whatsapp' es requerido");
        return cuentaService.crearCuenta(whatsapp);
    }

    @PostMapping("/cargos")
    public CuentaMovimientoResponse crearCargo(@RequestBody Map<String, Object> body) {
        var whatsapp = str(body.get("whatsapp"));
        if (whatsapp == null || whatsapp.isBlank())
            throw new IllegalArgumentException("'whatsapp' es requerido");
        var valor = body.get("valor") == null ? null : ((Number) body.get("valor")).longValue();
        return cuentaService.registrarCargo(
                whatsapp,
                str(body.get("concepto")),
                valor,
                str(body.get("mediaId")),
                str(body.get("mediaPath")),
                str(body.get("mimeType")));
    }

    @PostMapping("/{cuentaId}/abonos")
    public CuentaMovimientoResponse crearAbono(@PathVariable UUID cuentaId,
                                               @RequestBody Map<String, Object> body) {
        var valor = body.get("valor") == null ? null : ((Number) body.get("valor")).longValue();
        if (valor == null || valor <= 0) throw new IllegalArgumentException("'valor' es requerido");
        return cuentaService.registrarAbono(cuentaId, valor, str(body.get("referencia")), str(body.get("metodo")));
    }

    @PatchMapping("/movimientos/{id}/valor")
    public CuentaMovimientoResponse completarValor(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        var valor = body.get("valor") == null ? null : ((Number) body.get("valor")).longValue();
        if (valor == null || valor <= 0) throw new IllegalArgumentException("'valor' es requerido");
        return cuentaService.completarValor(id, valor);
    }

    @PatchMapping("/movimientos/{id}/concepto")
    public CuentaMovimientoResponse actualizarConcepto(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        var concepto = body.get("concepto");
        if (concepto == null || concepto.isBlank()) throw new IllegalArgumentException("'concepto' es requerido");
        return cuentaService.actualizarConcepto(id, concepto.trim());
    }

    @PatchMapping("/movimientos/{id}/foto")
    public CuentaMovimientoResponse adjuntarFoto(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return cuentaService.adjuntarFoto(id, body.get("mediaId"), body.get("mediaPath"), body.get("mimeType"));
    }

    @PostMapping("/movimientos/{id}/validar")
    public CuentaMovimientoResponse validar(@PathVariable UUID id) {
        return cuentaService.validarAbono(id);
    }

    @PostMapping("/movimientos/{id}/rechazar")
    public CuentaMovimientoResponse rechazar(@PathVariable UUID id) {
        return cuentaService.rechazarAbono(id);
    }

    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> subirMedia(@RequestParam("file") MultipartFile file) throws IOException {
        var fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        var targetDir = Paths.get(mediaDir, "cuentas");
        Files.createDirectories(targetDir);
        var targetPath = targetDir.resolve(fileName);
        file.transferTo(targetPath.toFile());
        var relativePath = "cuentas/" + fileName;
        log.info("Foto de cuenta guardada: {} ({} bytes)", relativePath, file.getSize());
        return Map.of("path", relativePath);
    }

    private static String str(Object o) {
        if (o == null) return null;
        var s = o.toString().trim();
        return s.isBlank() ? null : s;
    }
}
