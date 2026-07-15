package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.Cliente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClienteRepository extends JpaRepository<Cliente, UUID> {

    Optional<Cliente> findByWhatsapp(String whatsapp);
}
