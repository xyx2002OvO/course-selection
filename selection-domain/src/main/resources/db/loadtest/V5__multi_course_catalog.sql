-- Twelve independent open sections so confirm() does not serialize on course 201.
INSERT INTO course (id,term_id,title,capacity,remaining,credits,weekday,start_slot,end_slot,exclusion_group,prerequisite_id)
SELECT
  210 + n,
  202601,
  CONCAT('Loadtest Open ', 210 + n),
  100000,
  100000,
  1,
  1 + ((n - 1) % 5),
  1 + (((n - 1) DIV 5) * 2),
  2 + (((n - 1) DIV 5) * 2),
  NULL,
  NULL
FROM (
  SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
  UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
) seq;
