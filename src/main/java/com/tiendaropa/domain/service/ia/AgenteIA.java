package com.tiendaropa.domain.service.ia;

import java.util.Optional;

public interface AgenteIA {

    Optional<String> responder(String mensajeDelCliente, String contexto);

    default String nombre() {
        return getClass().getSimpleName();
    }
}
