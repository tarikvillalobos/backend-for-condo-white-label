UPDATE app_records SET location_id = payload::jsonb ->> '_id', version = version + 1,
  updated_at = to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
WHERE kind = 'v1_condominium' AND location_id IS NULL;
