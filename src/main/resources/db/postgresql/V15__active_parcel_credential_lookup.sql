CREATE INDEX parcel_active_credential_lookup ON app_records
  (tenant_id, location_id, (payload::jsonb ->> 'credentialHash'))
  WHERE kind='v1_parcel' AND payload::jsonb ->> 'credentialStatus'='active'
    AND payload::jsonb ->> '_deletedAt' IS NULL;
