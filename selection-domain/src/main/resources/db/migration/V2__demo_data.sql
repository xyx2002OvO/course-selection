INSERT INTO student_term_state (student_id, term_id, max_credits) VALUES
  (1001,202601,8),(1002,202601,8),(1003,202601,8),(1004,202601,8),(1005,202601,8);
INSERT INTO course (id,term_id,title,capacity,remaining,credits,weekday,start_slot,end_slot,exclusion_group,prerequisite_id) VALUES
  (101,202601,'Java Foundations',2,2,3,1,1,3,'language-intro',NULL),
  (102,202601,'Database Systems',2,2,3,1,2,4,NULL,NULL),
  (103,202601,'Distributed Systems',1,1,4,3,3,5,NULL,9001),
  (104,202601,'Python Foundations',2,2,3,4,1,3,'language-intro',NULL),
  (105,202601,'Systems Lab',2,2,6,5,1,5,NULL,NULL);
INSERT INTO passed_course VALUES (1001,9001),(1002,9001);
