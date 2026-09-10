SELECT state, COUNT(*) AS count FROM selection_request GROUP BY state;
SELECT kind,state,COUNT(*) AS count FROM outbox_event GROUP BY kind,state;
SELECT reason,COUNT(*) AS count FROM selection_request GROUP BY reason;
SELECT COUNT(*) AS enrollment_count FROM enrollment;
SELECT c.id,c.term_id,c.capacity,c.remaining,COUNT(e.request_id) AS enrolled
FROM course c LEFT JOIN enrollment e ON e.course_id=c.id AND e.term_id=c.term_id
GROUP BY c.id,c.term_id
HAVING c.remaining<0 OR COUNT(e.request_id)>c.capacity OR c.capacity-c.remaining<>COUNT(e.request_id);
SELECT s.request_id,s.state,e.request_id AS enrollment_request
FROM selection_request s LEFT JOIN enrollment e ON e.request_id=s.request_id
WHERE (s.state='SUCCESS' AND (e.request_id IS NULL OR e.student_id<>s.student_id
       OR e.term_id<>s.term_id OR e.course_id<>s.course_id))
   OR (s.state<>'SUCCESS' AND e.request_id IS NOT NULL);
SELECT e.request_id FROM enrollment e LEFT JOIN selection_request s ON s.request_id=e.request_id
WHERE s.request_id IS NULL;
SELECT s.request_id FROM selection_request s LEFT JOIN outbox_event o
ON o.request_id=s.request_id AND o.kind='RESULT'
WHERE s.state IN ('SUCCESS','REJECTED','CANCELLED') AND o.id IS NULL;
