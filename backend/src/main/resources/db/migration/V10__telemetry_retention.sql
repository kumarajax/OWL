CREATE TABLE telemetry_settings (
  id INT PRIMARY KEY CHECK (id = 1),
  max_retention_rows BIGINT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO telemetry_settings (id, max_retention_rows)
VALUES (1, 100000)
ON CONFLICT (id) DO NOTHING;
