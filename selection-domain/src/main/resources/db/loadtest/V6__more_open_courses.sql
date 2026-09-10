-- Extra open sections so 500 rps admission stays under the per-course hotspot (50 QPS).
INSERT INTO course (id,term_id,title,capacity,remaining,credits,weekday,start_slot,end_slot,exclusion_group,prerequisite_id)
SELECT
  222 + n,
  202601,
  CONCAT('Loadtest Open ', 222 + n),
  100000,
  100000,
  1,
  1 + ((n - 1) % 5),
  1 + (((n + 11) DIV 5) * 2),
  2 + (((n + 11) DIV 5) * 2),
  NULL,
  NULL
FROM (
  SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
) seq;
