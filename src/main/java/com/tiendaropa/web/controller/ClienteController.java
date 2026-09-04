package com.tiendaropa.web.controller;

import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.repository.ClienteRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Antes no existía ningún endpoint para listar clientes — se necesita acá para el selector
// de destinatarios de envíos masivos (Difusión). De solo lectura por ahora.
@RestController
@RequestMapping("/api/clientes")
@RequiredArgsConstructor
public class ClienteController {

    private final ClienteRepository repo;

    @GetMapping
    public List<Cliente> listar() {
        return repo.findAll();
    }
}
