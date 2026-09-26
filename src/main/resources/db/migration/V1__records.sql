CREATE TABLE app_mutex (id INTEGER PRIMARY KEY);
INSERT INTO app_mutex (id) VALUES (1);

CREATE TABLE app_records (
    id VARCHAR(128) PRIMARY KEY,
    kind VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    location_id VARCHAR(128),
    owner_id VARCHAR(128),
    payload TEXT NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX records_context ON app_records (tenant_id, kind, location_id);
CREATE INDEX records_owner ON app_records (tenant_id, kind, owner_id);
