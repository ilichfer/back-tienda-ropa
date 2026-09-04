-- Agrega la fecha en la que un pedido fue marcado como "enviado" desde el panel
-- (sección Pedidos → Envíos pendientes → botón "Marcar como enviado"). Se usa para
-- mostrarla en la pestaña "Enviados" y para saber desde cuándo está en tránsito.
DO $$ BEGIN ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS fecha_envio TIMESTAMPTZ; EXCEPTION WHEN others THEN NULL; END; $$;
