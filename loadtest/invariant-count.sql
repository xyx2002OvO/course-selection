SELECT
 (SELECT COUNT(*) FROM course c WHERE c.remaining<0 OR c.remaining>c.capacity
    OR c.capacity-c.remaining<>(SELECT COUNT(*) FROM enrollment e
                                WHERE e.course_id=c.id AND e.term_id=c.term_id))
 + (SELECT COUNT(*) FROM selection_request s LEFT JOIN enrollment e ON e.request_id=s.request_id
    WHERE (s.state='SUCCESS' AND (e.request_id IS NULL OR e.student_id<>s.student_id
          OR e.term_id<>s.term_id OR e.course_id<>s.course_id))
       OR (s.state<>'SUCCESS' AND e.request_id IS NOT NULL))
 + (SELECT COUNT(*) FROM enrollment e LEFT JOIN selection_request s ON s.request_id=e.request_id
    WHERE s.request_id IS NULL)
 + (SELECT COUNT(*) FROM selection_request s LEFT JOIN outbox_event o
    ON o.request_id=s.request_id AND o.kind='RESULT'
    WHERE s.state IN ('SUCCESS','REJECTED','CANCELLED') AND o.id IS NULL)
 AS invariant_violations;
