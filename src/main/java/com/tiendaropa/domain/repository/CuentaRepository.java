package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.Cuenta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CuentaRepository extends JpaRepository<Cuenta, UUID> {

    Optional<Cuenta> findByCliente_Whatsapp(String whatsapp);
}
