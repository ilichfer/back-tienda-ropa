package com.tiendaropa.domain.service.whatsapp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Meta firma cada webhook con el App Secret de la app (cabecera X-Hub-Signature-256 =
 * "sha256=" + HMAC-SHA256 del cuerpo exacto). Sin esta validación cualquiera podía hacerle POST
 * al webhook y meter mensajes falsos que el bot procesaba como si fueran de un cliente.
 *
 * Si whatsapp.app-secret está vacío, la validación queda desactivada (se acepta todo y se deja
 * un aviso en el log al arrancar) para no tumbar el webhook mientras se configura el secreto.
 */
@Component
@Slf4j
public class FirmaWebhookMeta {

    private static final String PREFIJO = "sha256=";

    private final byte[] secreto;

    public FirmaWebhookMeta(@Value("${whatsapp.app-secret:}") String appSecret) {
        this.secreto = appSecret == null || appSecret.isBlank() ? null : appSecret.trim().getBytes(StandardCharsets.UTF_8);
        if (this.secreto == null) {
            log.warn("[WEBHOOK] whatsapp.app-secret no está configurado: NO se valida la firma de los webhooks de Meta");
        }
    }

    public boolean activa() {
        return secreto != null;
    }

    /** true si la firma corresponde al cuerpo (o si la validación está desactivada). */
    public boolean esValida(byte[] cuerpo, String cabeceraFirma) {
        if (secreto == null) return true;
        if (cabeceraFirma == null || !cabeceraFirma.startsWith(PREFIJO)) return false;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto, "HmacSHA256"));
            var esperada = HexFormat.of().formatHex(mac.doFinal(cuerpo));
            var recibida = cabeceraFirma.substring(PREFIJO.length()).toLowerCase();
            // Comparación en tiempo constante, para no filtrar la firma por diferencias de tiempo.
            return MessageDigest.isEqual(esperada.getBytes(StandardCharsets.US_ASCII),
                    recibida.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            log.error("[WEBHOOK] No se pudo calcular la firma del webhook: {}", e.getMessage());
            return false;
        }
    }
}
