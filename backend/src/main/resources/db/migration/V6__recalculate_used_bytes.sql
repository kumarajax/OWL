UPDATE users u
SET used_bytes = COALESCE(usage.used_bytes, 0)
FROM (
  SELECT u.id, COALESCE(sum(f.size_bytes), 0) AS used_bytes
  FROM users u
  LEFT JOIN files f ON f.owner_id = u.id AND f.deleted_at IS NULL
  GROUP BY u.id
) usage
WHERE usage.id = u.id
  AND u.deactivated_at IS NULL;

UPDATE users
SET used_bytes = 0
WHERE deactivated_at IS NOT NULL;
