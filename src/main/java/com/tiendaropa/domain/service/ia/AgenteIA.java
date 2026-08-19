package com.tiendaropa.domain.service.ia;

import java.util.Optional;

public interface AgenteIA {

    Optional<String> responder(String mensajeDelCliente, String contexto);

    default Optional<AnalisisImagen> analizarImagen(byte[] imagen, String mimeType, String contexto) {
        return Optional.empty();
    }

    default String nombre() {
        return getClass().getSimpleName();
    }

    record AnalisisImagen(String tipo, String respuesta) {
        public boolean esPrenda() { return "PRENDA".equals(tipo); }
        public boolean esComprobante() { return "COMPROBANTE".equals(tipo); }
        public boolean esOtro() { return "OTRO".equals(tipo); }
    }
}
