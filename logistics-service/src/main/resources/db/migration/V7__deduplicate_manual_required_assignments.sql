WITH ranked_manual_required AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY order_id
            ORDER BY updated_at DESC, created_at DESC, id DESC
        ) AS row_number
    FROM courier_assignments
    WHERE assignment_status = 'MANUAL_REQUIRED'
      AND resolved_at IS NULL
)
UPDATE courier_assignments assignment
SET resolved_at = NOW()
FROM ranked_manual_required ranked
WHERE assignment.id = ranked.id
  AND ranked.row_number > 1;

DROP INDEX IF EXISTS ux_assignments_unresolved_manual_order;

CREATE UNIQUE INDEX ux_assignments_unresolved_manual_order
    ON courier_assignments(order_id)
    WHERE assignment_status = 'MANUAL_REQUIRED'
      AND resolved_at IS NULL;
