-- ============================================================
-- V1: baseline — reemplaza al viejo schema.sql + ddl-auto:update.
--
-- Este archivo es la migración #1 de Flyway. Se ejecuta de verdad tanto en una
-- base de datos nueva y vacía (crea todo desde cero) como en las bases de datos
-- que ya existen hoy (dev/prod) — ver spring.flyway.baseline-version en
-- application.yml, que está en 0 justamente para que este V1 se aplique de
-- verdad en todos los ambientes y no quede "saltado" como si ya estuviera
-- hecho. Es seguro: todo acá es idempotente (IF NOT EXISTS / ADD COLUMN IF
-- NOT EXISTS / DO...EXCEPTION), igual que lo era el schema.sql anterior.
--
-- A PARTIR DE ACÁ, cualquier cambio de esquema nuevo va en un archivo nuevo
-- V2__descripcion.sql, V3__descripcion.sql, etc. (nunca se edita este archivo
-- una vez que alguien ya lo corrió). Ver el comentario de spring.flyway en
-- application.yml para el detalle de por qué se abandonó ddl-auto:update +
-- schema.sql manual.
-- ============================================================

-- Los estados se guardan como VARCHAR (EnumType.STRING en JPA).
-- Se convierten los enums nativos de Postgres existentes (si hay) a VARCHAR.
DO $$ BEGIN ALTER TABLE pedidos ALTER COLUMN estado TYPE VARCHAR(20) USING estado::text; EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE pedido_eventos ALTER COLUMN estado TYPE VARCHAR(20) USING estado::text; EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE prendas ALTER COLUMN estado TYPE VARCHAR(20) USING estado::text; EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN DROP TYPE IF EXISTS estado_pedido; EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN DROP TYPE IF EXISTS estado_prenda; EXCEPTION WHEN others THEN NULL; END; $$;

-- Clientes
CREATE TABLE IF NOT EXISTS clientes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    whatsapp        VARCHAR(20) UNIQUE NOT NULL,
    nombre          VARCHAR(120),
    ciudad          VARCHAR(80),
    direccion       TEXT,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

-- Lotes: un lote por live de TikTok
CREATE TABLE IF NOT EXISTS lotes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre          VARCHAR(120) NOT NULL,
    fecha_live      DATE NOT NULL,
    descripcion     TEXT,
    activo          BOOLEAN DEFAULT true,
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- Prendas dentro de un lote
CREATE TABLE IF NOT EXISTS prendas (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id         UUID NOT NULL REFERENCES lotes(id),
    nombre          VARCHAR(120) NOT NULL,
    talla           VARCHAR(10),
    color           VARCHAR(50),
    precio          NUMERIC(12,2) NOT NULL,
    estado          VARCHAR(20) DEFAULT 'DISPONIBLE',
    foto_url        VARCHAR(500),
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

-- Pedidos
CREATE TABLE IF NOT EXISTS pedidos (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    numero          BIGSERIAL,
    cliente_id      UUID REFERENCES clientes(id),
    prenda_id       UUID REFERENCES prendas(id),
    estado          VARCHAR(20) DEFAULT 'NUEVO',
    precio_final    NUMERIC(12,2),
    costo_envio     NUMERIC(12,2) DEFAULT 12000,
    total           NUMERIC(12,2) GENERATED ALWAYS AS (precio_final + costo_envio) STORED,
    numero_guia     VARCHAR(80),
    transportadora  VARCHAR(50),
    notas           TEXT,
    nombre_dueño    VARCHAR(120),
    ubicacion       VARCHAR(20),
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

-- Historial de estados de pedido
CREATE TABLE IF NOT EXISTS pedido_eventos (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pedido_id   UUID NOT NULL REFERENCES pedidos(id),
    estado      VARCHAR(20) NOT NULL,
    nota        TEXT,
    created_at  TIMESTAMPTZ DEFAULT now()
);

-- requiere_asesor / bot_silenciado (Cliente): en dev existían por ddl-auto:update,
-- en prod (ddl-auto:none) no se habían creado nunca sin esto.
DO $$ BEGIN ALTER TABLE clientes ADD COLUMN IF NOT EXISTS requiere_asesor BOOLEAN DEFAULT FALSE; EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE clientes ADD COLUMN IF NOT EXISTS bot_silenciado BOOLEAN DEFAULT FALSE; EXCEPTION WHEN others THEN NULL; END; $$;

-- Columnas de bodega en pedidos (idempotente)
DO $$ BEGIN ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS nombre_dueño VARCHAR(120); EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS ubicacion VARCHAR(20); EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE pedidos ALTER COLUMN cliente_id DROP NOT NULL; EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE pedidos ALTER COLUMN prenda_id DROP NOT NULL; EXCEPTION WHEN others THEN NULL; END; $$;

-- Mensajes de WhatsApp
CREATE TABLE IF NOT EXISTS wa_mensajes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_id      UUID REFERENCES clientes(id),
    whatsapp_from   VARCHAR(50) NOT NULL,
    contenido       TEXT NOT NULL,
    tipo            VARCHAR(50) DEFAULT 'text',
    direccion       VARCHAR(10) NOT NULL,
    wa_message_id   VARCHAR(120),
    media_id        VARCHAR(255),
    media_path      VARCHAR(500),
    mime_type       VARCHAR(80),
    leido           BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ DEFAULT now()
);
DO $$ BEGIN ALTER TABLE wa_mensajes ADD COLUMN IF NOT EXISTS media_path VARCHAR(500); EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE wa_mensajes ADD COLUMN IF NOT EXISTS leido BOOLEAN DEFAULT FALSE; EXCEPTION WHEN others THEN NULL; END; $$;
-- whatsapp_from y tipo se declararon originalmente como VARCHAR(20), pero la entidad Java
-- (WaMensaje) siempre permitió hasta 50. En la práctica el "tipo" de un mensaje de botón se
-- guarda como "button_" + el id del botón (ej. "button_inconveniente_mas_fotos" = 30
-- caracteres), así que con VARCHAR(20) esa fila fallaba al guardarse. Se ensancha acá.
DO $$ BEGIN ALTER TABLE wa_mensajes ALTER COLUMN whatsapp_from TYPE VARCHAR(50); EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE wa_mensajes ALTER COLUMN tipo TYPE VARCHAR(50); EXCEPTION WHEN others THEN NULL; END; $$;

-- Plantillas de respuesta rápida
CREATE TABLE IF NOT EXISTS wa_plantillas (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        VARCHAR(50) UNIQUE NOT NULL,
    titulo      VARCHAR(80) NOT NULL,
    cuerpo      TEXT NOT NULL,
    activa      BOOLEAN DEFAULT true
);

-- Índices
CREATE INDEX IF NOT EXISTS idx_pedidos_estado   ON pedidos(estado);
CREATE INDEX IF NOT EXISTS idx_pedidos_cliente  ON pedidos(cliente_id);
CREATE INDEX IF NOT EXISTS idx_prendas_lote     ON prendas(lote_id);
CREATE INDEX IF NOT EXISTS idx_prendas_estado   ON prendas(estado);
CREATE INDEX IF NOT EXISTS idx_wa_mensajes_cliente ON wa_mensajes(cliente_id);
CREATE INDEX IF NOT EXISTS idx_wa_mensajes_from ON wa_mensajes(whatsapp_from);

-- Función y triggers para updated_at automático
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_clientes_upd ON clientes;
CREATE TRIGGER trg_clientes_upd BEFORE UPDATE ON clientes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_prendas_upd ON prendas;
CREATE TRIGGER trg_prendas_upd  BEFORE UPDATE ON prendas  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_pedidos_upd ON pedidos;
CREATE TRIGGER trg_pedidos_upd  BEFORE UPDATE ON pedidos  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Solicitudes de envío (desde WhatsApp)
CREATE TABLE IF NOT EXISTS solicitudes_envio (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_id      UUID REFERENCES clientes(id),
    whatsapp        VARCHAR(20) NOT NULL,
    nombre_completo VARCHAR(120),
    telefono        VARCHAR(20),
    cedula          VARCHAR(30),
    direccion       TEXT,
    ciudad          VARCHAR(80),
    barrio          VARCHAR(80),
    notas           TEXT,
    estado          VARCHAR(20) DEFAULT 'PENDIENTE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$ BEGIN ALTER TABLE solicitudes_envio ALTER COLUMN created_at SET DEFAULT now(); EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE solicitudes_envio ALTER COLUMN updated_at SET DEFAULT now(); EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN UPDATE solicitudes_envio SET created_at = now() WHERE created_at IS NULL; EXCEPTION WHEN undefined_table THEN NULL; END; $$;
DO $$ BEGIN UPDATE solicitudes_envio SET updated_at = now() WHERE updated_at IS NULL; EXCEPTION WHEN undefined_table THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE solicitudes_envio ALTER COLUMN created_at SET NOT NULL; EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE solicitudes_envio ALTER COLUMN updated_at SET NOT NULL; EXCEPTION WHEN others THEN NULL; END; $$;

CREATE INDEX IF NOT EXISTS idx_solicitudes_envio_estado ON solicitudes_envio(estado);

DROP TRIGGER IF EXISTS trg_solicitudes_envio_upd ON solicitudes_envio;
CREATE TRIGGER trg_solicitudes_envio_upd BEFORE UPDATE ON solicitudes_envio FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Cuentas por cobrar (una por cliente)
CREATE TABLE IF NOT EXISTS cuentas (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_id  UUID UNIQUE NOT NULL REFERENCES clientes(id),
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);

-- Movimientos de cuenta: CARGO (prenda que pidió) y ABONO (pago recibido)
CREATE TABLE IF NOT EXISTS cuentas_movimientos (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cuenta_id   UUID NOT NULL REFERENCES cuentas(id),
    tipo        VARCHAR(10) NOT NULL,
    concepto    VARCHAR(200),
    valor       BIGINT,
    estado      VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE',
    referencia  VARCHAR(120),
    metodo      VARCHAR(30),
    media_id    VARCHAR(255),
    media_path  VARCHAR(500),
    mime_type   VARCHAR(80),
    created_at  TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_cuentas_mov_cuenta ON cuentas_movimientos(cuenta_id);
CREATE INDEX IF NOT EXISTS idx_cuentas_mov_estado  ON cuentas_movimientos(estado);

-- Estado de conversación del bot de WhatsApp (flujo ENVIO/PEDIDO/INCONVENIENTE en curso),
-- persistido para que un reinicio del backend no borre lo que el cliente ya escribió.
CREATE TABLE IF NOT EXISTS wa_conversaciones (
    whatsapp_from   VARCHAR(20) PRIMARY KEY,
    flujo           VARCHAR(20) NOT NULL,
    paso            INT NOT NULL DEFAULT 0,
    datos           TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotencia de webhooks de WhatsApp: Meta puede reenviar el mismo webhook si no
-- confirmamos a tiempo, y sin esto el mensaje (y la lógica que dispara) se procesaría dos veces.
-- Antes de crear el índice único se limpian duplicados que ya existan en wa_mensajes (mensajes
-- guardados dos veces antes de que existiera esta protección), dejando solo uno por wa_message_id.
-- Es seguro correr esto en cada arranque: una vez limpio, no queda nada por borrar.
DELETE FROM wa_mensajes
    WHERE wa_message_id IS NOT NULL
    AND ctid NOT IN (
        SELECT MIN(ctid) FROM wa_mensajes
        WHERE wa_message_id IS NOT NULL
        GROUP BY wa_message_id
    );
CREATE UNIQUE INDEX IF NOT EXISTS idx_wa_mensajes_wa_message_id
    ON wa_mensajes(wa_message_id) WHERE wa_message_id IS NOT NULL;

-- Inconvenientes reportados por clientes (ropa rota, incompleta, etc.)
CREATE TABLE IF NOT EXISTS inconvenientes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_id      UUID REFERENCES clientes(id),
    whatsapp        VARCHAR(20) NOT NULL,
    tipo            VARCHAR(20) NOT NULL,
    descripcion     TEXT,
    estado          VARCHAR(20) NOT NULL DEFAULT 'RECIBIDO',
    pedido_id       VARCHAR(50),
    fotos           TEXT,
    notas_internas  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$ BEGIN ALTER TABLE inconvenientes ALTER COLUMN created_at SET DEFAULT now(); EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE inconvenientes ALTER COLUMN updated_at SET DEFAULT now(); EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN UPDATE inconvenientes SET created_at = now() WHERE created_at IS NULL; EXCEPTION WHEN undefined_table THEN NULL; END; $$;
DO $$ BEGIN UPDATE inconvenientes SET updated_at = now() WHERE updated_at IS NULL; EXCEPTION WHEN undefined_table THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE inconvenientes ALTER COLUMN created_at SET NOT NULL; EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE inconvenientes ALTER COLUMN updated_at SET NOT NULL; EXCEPTION WHEN others THEN NULL; END; $$;

CREATE INDEX IF NOT EXISTS idx_inconvenientes_estado ON inconvenientes(estado);

DROP TRIGGER IF EXISTS trg_inconvenientes_upd ON inconvenientes;
CREATE TRIGGER trg_inconvenientes_upd BEFORE UPDATE ON inconvenientes FOR EACH ROW EXECUTE FUNCTION set_updated_at();
