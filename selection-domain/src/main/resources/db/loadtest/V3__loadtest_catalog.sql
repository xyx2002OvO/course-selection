INSERT INTO student_term_state (student_id, term_id, max_credits)
WITH RECURSIVE seq AS (
  SELECT 0 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 999
)
SELECT 20001 + n, 202601, 8 FROM seq;
INSERT INTO student_term_state (student_id, term_id, max_credits)
WITH RECURSIVE seq AS (
  SELECT 0 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 999
)
SELECT 21001 + n, 202601, 8 FROM seq;
INSERT INTO student_term_state (student_id, term_id, max_credits)
WITH RECURSIVE seq AS (
  SELECT 0 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 999
)
SELECT 22001 + n, 202601, 8 FROM seq;
INSERT INTO student_term_state (student_id, term_id, max_credits)
WITH RECURSIVE seq AS (
  SELECT 0 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 999
)
SELECT 23001 + n, 202601, 8 FROM seq;
INSERT INTO course (id,term_id,title,capacity,remaining,credits,weekday,start_slot,end_slot,exclusion_group,prerequisite_id) VALUES
  (201,202601,'Loadtest Open',100000,100000,1,1,1,2,NULL,NULL),
  (202,202601,'Loadtest Scarce',50,50,1,2,1,2,NULL,NULL);
