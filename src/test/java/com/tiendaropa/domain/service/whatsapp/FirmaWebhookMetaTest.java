package com.tiendaropa.domain.service.whatsapp;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class FirmaWebhookMetaTest {

    private static final byte[] CUERPO = "{\"entry\":[]}".getBytes(StandardCharsets.UTF_8);

    private static String firmar(String secreto, byte[] cuerpo) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(cuerpo));
    }

    @Test
    void aceptaLaFirmaCorrecta() throws Exception {
        var firma = new FirmaWebhookMeta("secreto");
        assertThat(firma.esValida(CUERPO, firmar("secreto", CUERPO))).isTrue();
    }

    @Test
    void rechazaFirmaDeOtroSecretoAusenteOCuerpoAlterado() throws Exception {
        var firma = new FirmaWebhookMeta("secreto");
        assertThat(firma.esValida(CUERPO, firmar("otro", CUERPO))).isFalse();
        assertThat(firma.esValida(CUERPO, null)).isFalse();
        assertThat(firma.esValida("{\"entry\":[1]}".getBytes(StandardCharsets.UTF_8), firmar("secreto", CUERPO))).isFalse();
    }

    @Test
    void sinSecretoConfiguradoNoValida() {
        var firma = new FirmaWebhookMeta("");
        assertThat(firma.activa()).isFalse();
        assertThat(firma.esValida(CUERPO, null)).isTrue();
    }
}
