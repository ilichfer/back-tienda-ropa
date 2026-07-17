package com.tiendaropa.domain.service.impl;

import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.SolicitudEnvio;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.SolicitudEnvioRepository;
import com.tiendaropa.domain.service.EnvioService;
import com.tiendaropa.web.dto.response.EnvioResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class EnvioServiceImpl implements EnvioService {

    private final SolicitudEnvioRepository repo;
    private final ClienteRepository clienteRepo;

    @Override
    @Transactional(readOnly = true)
    public List<EnvioResponse> listar(String estado) {
        List<SolicitudEnvio> list;
        if (estado == null || estado.isBlank())
            list = repo.findAllByOrderByCreatedAtDesc();
        else
            list = repo.findByEstadoOrderByCreatedAtDesc(estado);
        return list.stream().map(EnvioResponse::from).toList();
    }

    @Override
    @Transactional
    public EnvioResponse cambiarEstado(UUID id, String nuevoEstado) {
        var envio = repo.findById(id).orElseThrow(() -> new RuntimeException("Solicitud no encontrada"));
        envio.setEstado(nuevoEstado);
        return EnvioResponse.from(repo.save(envio));
    }

    @Override
    @Transactional
    public SolicitudEnvio crearConDatos(String whatsapp, String nombre, String telefono,
                                        String cedula, String direccion, String ciudad, String barrio) {
        var cliente = clienteRepo.findByWhatsapp(whatsapp).orElse(null);
        var envio = SolicitudEnvio.builder()
            .whatsapp(whatsapp)
            .cliente(cliente)
            .nombreCompleto(nombre)
            .telefono(telefono)
            .cedula(cedula)
            .direccion(direccion)
            .ciudad(ciudad)
            .barrio(barrio)
            .notas("Nombre: %s\nTeléfono: %s\nCédula: %s\nDirección: %s\nCiudad: %s\nBarrio: %s"
                .formatted(nombre, telefono, cedula, direccion, ciudad, barrio))
            .estado("PENDIENTE")
            .build();
        envio = repo.save(envio);
        log.info("Solicitud de envío creada paso a paso desde {}: {}", whatsapp, envio.getId());
        return envio;
    }

    @Override
    @Transactional
    public SolicitudEnvio crearDesdeTexto(String whatsapp, String textoCrudo) {
        var cliente =  clienteRepo.findByWhatsapp(whatsapp).orElse(null);

        var datos = parsearTexto(textoCrudo);
        var envio = SolicitudEnvio.builder()
            .whatsapp(whatsapp)
            .cliente(cliente)
            .nombreCompleto(datos.getOrDefault("nombre", ""))
            .telefono(datos.getOrDefault("telefono", ""))
            .cedula(datos.getOrDefault("cedula", ""))
            .direccion(datos.getOrDefault("direccion", ""))
            .ciudad(datos.getOrDefault("ciudad", ""))
            .barrio(datos.getOrDefault("barrio", ""))
            .notas(textoCrudo)
            .estado("PENDIENTE")
            .build();

        envio = repo.save(envio);
        log.info("Solicitud de envío creada desde {}: {}", whatsapp, envio.getId());
        return envio;
    }

    Map<String, String> parsearTexto(String texto) {
        var result = new java.util.HashMap<String, String>();
        if (texto == null || texto.isBlank()) return result;

        var lines = texto.split("\\n");
        var patterns = Map.of(
            "nombre",    List.of("nombre completo", "nombre", "nombre:"),
            "telefono",  List.of("tel\u00e9fono", "telefono", "tel\u00e9fono:", "telefono:", "celular", "celular:", "tel:"),
            "cedula",    List.of("c\u00e9dula", "cedula", "c\u00e9dula:", "cedula:", "cc:", "documento", "documento:"),
            "direccion", List.of("direcci\u00f3n", "direccion", "direcci\u00f3n:", "direccion:", "dir:", "dirección:"),
            "ciudad",    List.of("ciudad", "ciudad:"),
            "barrio",    List.of("barrio", "barrio:")
        );

        for (var line : lines) {
            var trimmed = line.trim();
            if (trimmed.isBlank() || !trimmed.contains(":")) continue;

            for (var entry : patterns.entrySet()) {
                if (result.containsKey(entry.getKey())) continue;
                for (var prefix : entry.getValue()) {
                    if (trimmed.toLowerCase().startsWith(prefix.toLowerCase())) {
                        var val = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                        if (!val.isBlank()) {
                            result.put(entry.getKey(), val);
                        }
                        break;
                    }
                }
            }
        }
        return result;
    }
}
