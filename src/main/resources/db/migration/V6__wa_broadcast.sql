-- Envíos masivos (broadcast) usando SOLO plantillas ya aprobadas por Meta en WhatsApp
-- Manager — es la única forma correcta de alcanzar clientes fuera de la ventana de 24h.
-- wa_plantillas_meta es un simple registro local de "esta plantilla existe y aprobada,
-- se llama así, en este idioma, y pide estas variables" — la app no crea ni aprueba
-- plantillas ante Meta, eso se sigue haciendo en WhatsApp Manager.
CREATE TABLE IF NOT EXISTS wa_plantillas_meta (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre          VARCHAR(120) UNIQUE NOT NULL,
    idioma          VARCHAR(10) NOT NULL DEFAULT 'es',
    -- Etiquetas de las variables {{1}}, {{2}}... en orden, separadas por coma (ej. "nombre,guia").
    variables       VARCHAR(300),
    descripcion     TEXT,
    activa          BOOLEAN DEFAULT true,
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- Una "campaña" de difusión: qué plantilla, con qué configuración de variables
-- (texto fijo para todos, o "nombre del cliente" para personalizar por destinatario).
CREATE TABLE IF NOT EXISTS wa_broadcasts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plantilla_meta_id   UUID REFERENCES wa_plantillas_meta(id),
    variables_config    TEXT,
    total               INT DEFAULT 0,
    enviados            INT DEFAULT 0,
    fallidos            INT DEFAULT 0,
    estado              VARCHAR(20) DEFAULT 'PENDIENTE',
    created_at          TIMESTAMPTZ DEFAULT now()
);

-- Un renglón por destinatario de la campaña, para auditoría y detalle de errores.
CREATE TABLE IF NOT EXISTS wa_broadcast_envios (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    broadcast_id    UUID NOT NULL REFERENCES wa_broadcasts(id),
    whatsapp_from   VARCHAR(50) NOT NULL,
    estado          VARCHAR(20) DEFAULT 'PENDIENTE',
    wa_message_id   VARCHAR(120),
    error           TEXT
);

CREATE INDEX IF NOT EXISTS idx_wa_broadcast_envios_broadcast ON wa_broadcast_envios(broadcast_id);
