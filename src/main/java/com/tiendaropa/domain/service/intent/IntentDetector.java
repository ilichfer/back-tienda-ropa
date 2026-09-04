package com.tiendaropa.domain.service.intent;

import org.springframework.stereotype.Component;

/**
 * Detección determinística de intención por palabra clave, para todo lo que el mensaje del
 * cliente debe disparar SIEMPRE igual (precio, saldo, cómo pagar, etc.) sin depender de que
 * la IA lo interprete bien. Antes estos métodos vivían como private sueltos dentro de
 * WhatsAppServiceImpl, mezclados con la orquestación de los flujos — se agrupan acá para que
 * quede en un solo lugar cada vez que se agregue una intención nueva, y para poder probarlos
 * (son funciones puras: String -> boolean, sin dependencias de base de datos ni de WhatsApp).
 *
 * IMPORTANTE: acá solo va la DETECCIÓN ("¿el mensaje significa X?"). Qué hacer con eso
 * (responderComoPagar, responderEscalarPrecio, responderSaldo, etc.) se queda en
 * WhatsAppServiceImpl porque esas sí dependen de repositorios y de enviar mensajes.
 */
@Component
public class IntentDetector {

    // Detecta cuando el cliente pregunta CÓMO o A DÓNDE pagar (método/número de pago), en vez
    // de CUÁNTO debe (eso lo cubre esIntencionSaldo). Se revisa antes que esIntencionSaldo
    // porque frases como "cómo puedo pagar lo que debo" contienen "que debo" y sin este orden
    // se responderían con el saldo en vez de con el número para pagar, que es lo que realmente
    // preguntan.
    public boolean esIntencionComoPagar(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase();
        return t.contains("como pago") || t.contains("cómo pago")
                || t.contains("como puedo pagar") || t.contains("cómo puedo pagar")
                || t.contains("como hago el pago") || t.contains("cómo hago el pago")
                || t.contains("como hago para pagar") || t.contains("cómo hago para pagar")
                || t.contains("donde pago") || t.contains("dónde pago")
                || t.contains("donde consigno") || t.contains("dónde consigno")
                || t.contains("adonde consigno") || t.contains("adónde consigno")
                || t.contains("a donde consigno") || t.contains("a dónde consigno")
                || t.contains("como consigno") || t.contains("cómo consigno")
                || t.contains("numero para pagar") || t.contains("número para pagar")
                || t.contains("numero de pago") || t.contains("número de pago")
                || t.contains("numero para consignar") || t.contains("número para consignar")
                || t.contains("a que numero") || t.contains("a qué número")
                || t.contains("nequi") || t.contains("daviplata");
    }

    // Detecta cuando el cliente pregunta el precio/valor de una prenda. El agente no tiene
    // forma confiable de saber los precios reales (cambian según el lote/lo que se vendió en
    // el live), así que esto siempre se escala a un asesor humano en vez de dejar que la IA
    // conteste con un valor inventado.
    public boolean esIntencionPrecio(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase();
        return t.contains("precio") || t.contains("precios")
                || t.contains("que precio") || t.contains("qué precio")
                || t.contains("que valor") || t.contains("qué valor")
                || t.contains("cuanto cuesta") || t.contains("cuánto cuesta")
                || t.contains("cuanto cuestan") || t.contains("cuánto cuestan")
                || t.contains("cuanto vale") || t.contains("cuánto vale")
                || t.contains("cuanto valen") || t.contains("cuánto valen")
                || t.contains("cuanto sale") || t.contains("cuánto sale")
                || t.contains("cuanto salen") || t.contains("cuánto salen")
                || t.contains("a como") || t.contains("a cómo");
    }

    public boolean esIntencionSaldo(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase();
        return t.contains("cuanto debo") || t.contains("cuánto debo")
                || t.contains("cuanto pago") || t.contains("cuánto pago")
                || t.contains("cuanto cancelo") || t.contains("cuánto cancelo")
                || t.contains("cuanto debo cancelar") || t.contains("cuánto debo cancelar")
                || t.contains("cuanto debo pagar") || t.contains("cuánto debo pagar")
                || t.contains("cuanto me falta") || t.contains("cuánto me falta")
                || t.contains("cuanto es mi deuda") || t.contains("cuánto es mi deuda")
                || t.contains("mi saldo") || t.contains("el saldo")
                || t.contains("que debo") || t.contains("qué debo")
                || t.contains("deuda") || t.contains("saldo")
                || t.contains("cuanto debo en total") || t.contains("cuánto debo en total");
    }

    public boolean esIntencionPedido(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase();
        return t.contains("pedir") || t.contains("pedí") || t.contains("pedi")
                || t.contains("apartar") || t.contains("aparta") || t.contains("apart")
                || t.contains("guárdame") || t.contains("guardame") || t.contains("guárdamelo")
                || t.contains("reservar") || t.contains("reserva") || t.contains("pedido");
    }

    // Detecta cuando el cliente avisa que no puede/pudo tomar (o mandar) la foto de la prenda
    // que quiere apartar. En ese caso se arranca el mismo flujo de apartar por texto que ya
    // existe (iniciarFlujoPedidoTexto): se le pregunta qué prenda es y su valor, y se registra
    // el cargo en la cuenta sin necesidad de una foto — así el control de lo que debe no
    // depende de que el cliente logre mandar la imagen.
    public boolean esIntencionSinFoto(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase();
        return t.contains("no pude tomar") || t.contains("no puedo tomar")
                || t.contains("no tengo foto") || t.contains("no tengo la foto")
                || t.contains("no logré tomar") || t.contains("no logre tomar")
                || t.contains("sin foto") || t.contains("no me dejó tomar")
                || t.contains("no me dejo tomar") || t.contains("no pude sacar")
                || t.contains("no puedo sacar") || t.contains("no tengo cómo tomar")
                || t.contains("no tengo como tomar") || t.contains("no hay foto")
                || t.contains("se me dañó la foto") || t.contains("se me dano la foto")
                || t.contains("no me tomó la foto") || t.contains("no me tomo la foto")
                || t.contains("no puedo mandar foto") || t.contains("no puedo enviar foto")
                || t.contains("no puedo enviar la foto") || t.contains("no puedo mandar la foto");
    }

    // Palabras/frases que indican que el cliente quiere el ENVÍO de un pedido que ya hizo
    // (distinto de "hacer un pedido nuevo"). Se evalúa ANTES que esIntencionPedido para que
    // frases como "quiero mi pedido" no terminen abriendo por error el flujo de apartar prenda.
    // Este chequeo por palabra clave existe como respaldo del marcador [SOLICITAR_ENVIO] que
    // devuelve el agente IA: si la IA está deshabilitada, falla o no sigue el prompt, esta
    // regla igual detecta la intención más común y arranca el flujo de recolección de datos.
    public boolean esIntencionEnvio(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase();
        return t.contains("envíame") || t.contains("enviame")
                || t.contains("envíenme") || t.contains("envienme")
                || t.contains("me envías") || t.contains("me envias")
                || t.contains("me envíes") || t.contains("me envies")
                || t.contains("mi envío") || t.contains("mi envio")
                || t.contains("quiero mi pedido") || t.contains("quiero mi ropa")
                || t.contains("quiero lo mío") || t.contains("quiero lo mio")
                || t.contains("envía mi pedido") || t.contains("envia mi pedido")
                || t.contains("envía mi ropa") || t.contains("envia mi ropa")
                || t.contains("manda mi pedido") || t.contains("manden mi pedido")
                || t.contains("mándame mi pedido") || t.contains("mandame mi pedido")
                || t.contains("cuando me envían") || t.contains("cuando me envian")
                || t.contains("cuándo me envían") || t.contains("cuándo me envian");
    }

    public boolean esIntencionInconveniente(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase();
        return t.contains("roto") || t.contains("dañado") || t.contains("danado")
                || t.contains("imperfecto") || t.contains("imperfecta") || t.contains("mancha")
                || t.contains("incompleto") || t.contains("incompleta") || t.contains("faltante")
                || t.contains("mal estado") || t.contains("no llegó") || t.contains("no llego")
                || t.contains("me falta") || t.contains("llegó mal") || t.contains("llego mal")
                || t.contains("problema con") || t.contains("reclamo") || t.contains("queja")
                || t.contains("rotas") || t.contains("dañadas") || t.contains("manchada")
                || t.contains("imperfeccion")
                // Variantes de "faltar" que "me falta" no cubre (ej. "me hizo falta una
                // prenda", "faltó una prenda") — antes esto caía hasta esIntencionPedido, que
                // matchea con la palabra suelta "pedido" y terminaba abriendo por error el
                // flujo de apartar una prenda nueva en vez del de inconveniente.
                || t.contains("hizo falta") || t.contains("hicieron falta")
                || t.contains("faltó") || t.contains("falto");
    }

    // Detecta cuando el cliente pregunta por el horario/fecha del próximo live de TikTok. No
    // tenemos forma de saber con certeza cuándo va a ser cada live (se define y anuncia aparte),
    // así que esto no se escala a un asesor ni se inventa una fecha: se le indica al cliente que
    // esté pendiente a las publicaciones y, sobre todo, que se una al grupo de WhatsApp, que es
    // donde se avisa el día y la hora.
    public boolean esIntencionHorarioLive(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase();
        return t.contains("cuando es el live") || t.contains("cuándo es el live")
                || t.contains("cuando el live") || t.contains("cuándo el live")
                || t.contains("cuando sera el live") || t.contains("cuándo será el live")
                || t.contains("cuando es el proximo live") || t.contains("cuándo es el próximo live")
                || t.contains("proximo live") || t.contains("próximo live")
                || t.contains("cuando transmiten") || t.contains("cuándo transmiten")
                || t.contains("cuando van a transmitir") || t.contains("cuándo van a transmitir")
                || t.contains("a que hora es el live") || t.contains("a qué hora es el live")
                || t.contains("a que hora transmiten") || t.contains("a qué hora transmiten")
                || t.contains("hora del live") || t.contains("horario del live")
                || t.contains("cuando hacen live") || t.contains("cuándo hacen live")
                || t.contains("cuando es la live") || t.contains("cuándo es la live")
                || t.contains("cuando van a hacer live") || t.contains("cuándo van a hacer live")
                || t.contains("hoy hay live") || t.contains("hay live hoy")
                || (t.contains("live") && (t.contains("cuando") || t.contains("cuándo") || t.contains("hora") || t.contains("horario")));
    }

    // Detecta cuando el cliente escribe (en vez de tocar el botón) que ya no tiene más fotos
    // que enviar en el flujo de inconveniente — equivalente al botón "✅ Listo, guardar".
    public boolean esListo(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase().trim();
        return t.equals("listo") || t.equals("ya") || t.equals("ya esta") || t.equals("ya está")
                || t.equals("no") || t.equals("nop")
                || t.contains("no tengo mas fotos") || t.contains("no tengo más fotos")
                || t.contains("no tengo mas foto") || t.contains("no tengo más foto")
                || t.contains("ya no tengo mas") || t.contains("ya no tengo más")
                || t.contains("eso es todo") || t.contains("es todo")
                || t.contains("ninguna mas") || t.contains("ninguna más")
                || t.contains("no hay mas") || t.contains("no hay más")
                || t.contains("solo esa") || t.contains("solo esas")
                || t.contains("guardalo") || t.contains("guárdalo") || t.contains("guardar así") || t.contains("guardar asi");
    }

    // Detecta cuando el cliente escribe (en vez de tocar el botón) que SÍ tiene más fotos del
    // problema por enviar — equivalente al botón "📸 Sí, tengo más".
    public boolean esQuiereMasFotos(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase().trim();
        return t.equals("si") || t.equals("sí")
                || t.contains("mas fotos") || t.contains("más fotos")
                || t.contains("tengo mas") || t.contains("tengo más")
                || t.contains("una mas") || t.contains("una más")
                || t.contains("otra foto") || t.contains("otra mas") || t.contains("otra más");
    }

    // Detecta cuando el cliente pregunta por el horario/días de despacho de los envíos EN
    // GENERAL ("¿cuándo son los envíos?", "¿qué días envían?") — distinto de esIntencionEnvio,
    // que es cuando el cliente pide/reclama SU propio envío ("quiero mi envío", "mi pedido").
    // Aquí sí hay una respuesta fija y correcta (a diferencia del precio), así que se responde
    // directo en vez de escalar a asesor — evita que la IA invente días u origen del envío
    // (pasó en producción: inventó "martes y viernes desde Bogotá", el "desde Bogotá" era
    // inventado por la IA).
    public boolean esIntencionInfoEnvios(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase();
        return t.contains("cuando son los envios") || t.contains("cuándo son los envíos")
                || t.contains("cuando son los envíos") || t.contains("cuándo son los envios")
                || t.contains("que dias envian") || t.contains("qué días envían")
                || t.contains("que dia envian") || t.contains("qué día envían")
                || t.contains("cuando despachan") || t.contains("cuándo despachan")
                || t.contains("que dias despachan") || t.contains("qué días despachan")
                || t.contains("dias de despacho") || t.contains("días de despacho")
                || t.contains("dia de despacho") || t.contains("día de despacho")
                || t.contains("cuando hacen los envios") || t.contains("cuándo hacen los envíos")
                || t.contains("cuando hacen envios") || t.contains("cuándo hacen envíos")
                || t.contains("horario de envio") || t.contains("horario de envío")
                || t.contains("horario de despacho") || t.contains("dias de envio") || t.contains("días de envío")
                || t.contains("cuando envian") || t.contains("cuándo envían");
    }

    public boolean esNoSe(String contenido) {
        if (contenido == null) return false;
        var t = contenido.toLowerCase().trim();
        return t.equals("no se") || t.equals("no sé") || t.equals("nose") || t.equals("no se el valor") || t.equals("no se cuanto vale");
    }
}
