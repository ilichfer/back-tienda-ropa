-- ============================================================
-- SCHEMA: Sistema de gestión de ropa TikTok Live
-- Idempotente: se puede ejecutar múltiples veces sin errores
-- ============================================================

-- Los estados se guardan como VARCHAR (EnumType.STRING en JPA).
-- Se convierten los enums nativos de Postgres existentes (si hay) a VARCHAR.
DO $$ BEGIN ALTER TABLE pedidos ALTER COLUMN estado TYPE VARCHAR(20) USING estado::text; EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN ALTER TABLE pedido_eventos ALTER COLUMN estado TYPE VARCHAR(20) USING estado::text; EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN ALTER TABLE prendas ALTER COLUMN estado TYPE VARCHAR(20) USING estado::text; EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN DROP TYPE IF EXISTS estado_pedido; EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN DROP TYPE IF EXISTS estado_prenda; EXCEPTION WHEN others THEN NULL; END; $$ //

-- Clientes
CREATE TABLE IF NOT EXISTS clientes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    whatsapp        VARCHAR(20) UNIQUE NOT NULL,
    nombre          VARCHAR(120),
    ciudad          VARCHAR(80),
    direccion       TEXT,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
) //

-- Lotes: un lote por live de TikTok
CREATE TABLE IF NOT EXISTS lotes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre          VARCHAR(120) NOT NULL,
    fecha_live      DATE NOT NULL,
    descripcion     TEXT,
    activo          BOOLEAN DEFAULT true,
    created_at      TIMESTAMPTZ DEFAULT now()
) //

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
) //

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
) //

-- Historial de estados de pedido
CREATE TABLE IF NOT EXISTS pedido_eventos (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pedido_id   UUID NOT NULL REFERENCES pedidos(id),
    estado      VARCHAR(20) NOT NULL,
    nota        TEXT,
    created_at  TIMESTAMPTZ DEFAULT now()
) //

-- Columnas de bodega en pedidos (idempotente)
DO $$ BEGIN ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS nombre_dueño VARCHAR(120); EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS ubicacion VARCHAR(20); EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN ALTER TABLE pedidos ALTER COLUMN cliente_id DROP NOT NULL; EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN ALTER TABLE pedidos ALTER COLUMN prenda_id DROP NOT NULL; EXCEPTION WHEN others THEN NULL; END; $$ //

-- Mensajes de WhatsApp
CREATE TABLE IF NOT EXISTS wa_mensajes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_id      UUID REFERENCES clientes(id),
    whatsapp_from   VARCHAR(20) NOT NULL,
    contenido       TEXT NOT NULL,
    tipo            VARCHAR(20) DEFAULT 'text',
    direccion       VARCHAR(10) NOT NULL,
    wa_message_id   VARCHAR(120),
    media_id        VARCHAR(255),
    media_path      VARCHAR(500),
    mime_type       VARCHAR(80),
    leido           BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ DEFAULT now()
) //
DO $$ BEGIN ALTER TABLE wa_mensajes ADD COLUMN IF NOT EXISTS media_path VARCHAR(500); EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN ALTER TABLE wa_mensajes ADD COLUMN IF NOT EXISTS leido BOOLEAN DEFAULT FALSE; EXCEPTION WHEN others THEN NULL; END; $$ //

-- Plantillas de respuesta rápida
CREATE TABLE IF NOT EXISTS wa_plantillas (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        VARCHAR(50) UNIQUE NOT NULL,
    titulo      VARCHAR(80) NOT NULL,
    cuerpo      TEXT NOT NULL,
    activa      BOOLEAN DEFAULT true
) //

-- Índices
CREATE INDEX IF NOT EXISTS idx_pedidos_estado   ON pedidos(estado) //
CREATE INDEX IF NOT EXISTS idx_pedidos_cliente  ON pedidos(cliente_id) //
CREATE INDEX IF NOT EXISTS idx_prendas_lote     ON prendas(lote_id) //
CREATE INDEX IF NOT EXISTS idx_prendas_estado   ON prendas(estado) //
CREATE INDEX IF NOT EXISTS idx_wa_mensajes_cliente ON wa_mensajes(cliente_id) //
CREATE INDEX IF NOT EXISTS idx_wa_mensajes_from ON wa_mensajes(whatsapp_from) //

-- Función y triggers para updated_at automático
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$ LANGUAGE plpgsql //

DROP TRIGGER IF EXISTS trg_clientes_upd ON clientes //
CREATE TRIGGER trg_clientes_upd BEFORE UPDATE ON clientes FOR EACH ROW EXECUTE FUNCTION set_updated_at() //

DROP TRIGGER IF EXISTS trg_prendas_upd ON prendas //
CREATE TRIGGER trg_prendas_upd  BEFORE UPDATE ON prendas  FOR EACH ROW EXECUTE FUNCTION set_updated_at() //

DROP TRIGGER IF EXISTS trg_pedidos_upd ON pedidos //
CREATE TRIGGER trg_pedidos_upd  BEFORE UPDATE ON pedidos  FOR EACH ROW EXECUTE FUNCTION set_updated_at() //

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
) //

DO $$ BEGIN ALTER TABLE solicitudes_envio ALTER COLUMN created_at SET DEFAULT now(); EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN ALTER TABLE solicitudes_envio ALTER COLUMN updated_at SET DEFAULT now(); EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN UPDATE solicitudes_envio SET created_at = now() WHERE created_at IS NULL; EXCEPTION WHEN undefined_table THEN NULL; END; $$ //
DO $$ BEGIN UPDATE solicitudes_envio SET updated_at = now() WHERE updated_at IS NULL; EXCEPTION WHEN undefined_table THEN NULL; END; $$ //
DO $$ BEGIN ALTER TABLE solicitudes_envio ALTER COLUMN created_at SET NOT NULL; EXCEPTION WHEN others THEN NULL; END; $$ //
DO $$ BEGIN ALTER TABLE solicitudes_envio ALTER COLUMN updated_at SET NOT NULL; EXCEPTION WHEN others THEN NULL; END; $$ //

CREATE INDEX IF NOT EXISTS idx_solicitudes_envio_estado ON solicitudes_envio(estado) //

DROP TRIGGER IF EXISTS trg_solicitudes_envio_upd ON solicitudes_envio //
CREATE TRIGGER trg_solicitudes_envio_upd BEFORE UPDATE ON solicitudes_envio FOR EACH ROW EXECUTE FUNCTION set_updated_at() //

-- Cuentas por cobrar (una por cliente)
CREATE TABLE IF NOT EXISTS cuentas (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_id  UUID UNIQUE NOT NULL REFERENCES clientes(id),
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
) //

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
) //

CREATE INDEX IF NOT EXISTS idx_cuentas_mov_cuenta ON cuentas_movimientos(cuenta_id) //
CREATE INDEX IF NOT EXISTS idx_cuentas_mov_estado  ON cuentas_movimientos(estado) //
