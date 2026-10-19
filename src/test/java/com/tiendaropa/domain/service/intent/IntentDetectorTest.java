package com.tiendaropa.domain.service.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IntentDetectorTest {

    private final IntentDetector detector = new IntentDetector();

    @ParameterizedTest
    @ValueSource(strings = {
            "me hizo falta una prenda en el pedido",
            "una no me llegó",
            "la blusa llegó rota",
            "faltó una prenda",
            "tengo un reclamo",
    })
    void reclamosSeDetectanComoInconveniente(String mensaje) {
        assertThat(detector.esIntencionInconveniente(mensaje)).isTrue();
    }

    @Test
    void quieroMiPedidoEsEnvioYNoUnPedidoNuevo() {
        // esIntencionEnvio se evalúa antes que esIntencionPedido justamente por esta frase.
        assertThat(detector.esIntencionEnvio("quiero mi pedido")).isTrue();
    }

    @Test
    void comoPagarNoSeConfundeConElSaldo() {
        var mensaje = "cómo puedo pagar lo que debo";
        assertThat(detector.esIntencionComoPagar(mensaje)).isTrue();
        // También matchea saldo: por eso el orden de evaluación importa.
        assertThat(detector.esIntencionSaldo(mensaje)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cuánto cuesta esta", "qué precio tiene", "a como la chaqueta"})
    void preguntasDePrecio(String mensaje) {
        assertThat(detector.esIntencionPrecio(mensaje)).isTrue();
    }

    @Test
    void comprobanteEsEstrictoConLaPalabraPago() {
        assertThat(detector.esIntencionComprobantePago("ya pagué")).isTrue();
        assertThat(detector.esIntencionComprobantePago("esta la pago mañana")).isFalse();
        // En la pregunta de los botones de la foto sí vale "pago" suelto.
        assertThat(detector.esRespuestaFotoPago("es un pago")).isTrue();
    }

    @Test
    void horarioDelLive() {
        assertThat(detector.esIntencionHorarioLive("¿a qué hora es el live?")).isTrue();
        assertThat(detector.esIntencionHorarioLive("me gustó el live")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "quiero hablar con un asesor",
            "Me comunicas con una ASESORA?",
            "necesito hablar con una persona",
            "no quiero hablar con un bot",
    })
    void pedirUnaPersona(String mensaje) {
        assertThat(detector.esIntencionHablarConAsesor(mensaje)).isTrue();
    }

    @Test
    void preguntarPorUnaPrendaNoEsPedirAsesor() {
        assertThat(detector.esIntencionHablarConAsesor("tienen esta blusa en talla M?")).isFalse();
    }

    @Test
    void textoNuloNuncaEsUnaIntencion() {
        assertThat(detector.esIntencionEnvio(null)).isFalse();
        assertThat(detector.esIntencionInconveniente(null)).isFalse();
        assertThat(detector.esListo(null)).isFalse();
    }
}
