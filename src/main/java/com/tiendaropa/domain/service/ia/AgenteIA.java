package com.tiendaropa.domain.service.ia;

import java.util.Optional;

public interface AgenteIA {

    Optional<String> responder(String mensajeDelCliente, String contexto);

    default Optional<AnalisisImagen> analizarImagen(byte[] imagen, String mimeType, String contexto) {
        return Optional.empty();
    }

    /**
     * Interpreta una nota de voz del cliente y devuelve una respuesta lista para enviar,
     * igual que responder(). Un proveedor que no lo soporte simplemente no sobreescribe este
     * método: el default hace que WhatsAppServiceImpl caiga de vuelta al comportamiento
     * anterior (el audio queda guardado en el historial pero no se interpreta) sin romper nada.
     */
    default Optional<String> responderAudio(byte[] audio, String mimeType, String contexto) {
        return Optional.empty();
    }

    default String nombre() {
        return getClass().getSimpleName();
    }

    record AnalisisImagen(String tipo, String respuesta) {
        public boolean esPrenda() { return "PRENDA".equals(tipo); }
        public boolean esComprobante() { return "COMPROBANTE".equals(tipo); }
        public boolean esDanado() { return "DANO".equals(tipo); }
        public boolean esOtro() { return "OTRO".equals(tipo); }
    }
}
