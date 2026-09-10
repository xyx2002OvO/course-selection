UPDATE course SET capacity = 500000, remaining = 500000 WHERE id = 201 AND term_id = 202601;

INSERT INTO student_term_state (student_id, term_id, max_credits)
WITH RECURSIVE ones AS (
  SELECT 0 AS n
  UNION ALL
  SELECT n + 1 FROM ones WHERE n < 999
),
blocks AS (
  SELECT 0 AS n
  UNION ALL
  SELECT n + 1 FROM blocks WHERE n < 55
)
SELECT 24001 + ones.n + blocks.n * 1000, 202601, 8
FROM ones
CROSS JOIN blocks
WHERE 24001 + ones.n + blocks.n * 1000 <= 80000;
