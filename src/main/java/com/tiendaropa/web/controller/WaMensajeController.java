package com.tiendaropa.web.controller;

import com.tiendaropa.domain.model.WaMensaje;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wa-mensajes")
@RequiredArgsConstructor
public class WaMensajeController {

    private final WaMensajeRepository waMensajeRepo;

    @GetMapping
    public List<WaMensaje> listar(@RequestParam(required = false) String whatsapp) {
        if (whatsapp != null) {
            return waMensajeRepo.findByWhatsappFromOrderByCreatedAtDesc(whatsapp);
        }
        return waMensajeRepo.findAll();
    }
}
