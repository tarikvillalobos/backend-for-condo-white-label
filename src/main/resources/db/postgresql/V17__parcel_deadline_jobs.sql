CREATE INDEX parcel_deadline_jobs ON app_records (created_at,id)
  WHERE kind='v1_parcel' AND payload::jsonb ->> 'status' IN ('waiting','manual')
    AND payload::jsonb ->> 'deadlineNearNotifiedAt' IS NULL
    AND payload::jsonb ->> '_deletedAt' IS NULL;
