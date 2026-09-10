CREATE TABLE student_term_state (
  student_id BIGINT NOT NULL,
  term_id BIGINT NOT NULL,
  max_credits INT NOT NULL,
  PRIMARY KEY (student_id, term_id)
);
CREATE TABLE course (
  id BIGINT NOT NULL,
  term_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL,
  capacity INT NOT NULL,
  remaining INT NOT NULL,
  credits INT NOT NULL,
  weekday INT NOT NULL,
  start_slot INT NOT NULL,
  end_slot INT NOT NULL,
  exclusion_group VARCHAR(40),
  prerequisite_id BIGINT,
  PRIMARY KEY (id, term_id),
  CHECK (remaining >= 0 AND remaining <= capacity)
);
CREATE TABLE passed_course (
  student_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  PRIMARY KEY (student_id, course_id)
);
CREATE TABLE selection_request (
  request_id CHAR(36) PRIMARY KEY,
  student_id BIGINT NOT NULL,
  term_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  reason VARCHAR(80) NOT NULL DEFAULT '',
  deadline TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX ix_request_deadline (state, deadline)
);
CREATE TABLE enrollment (
  student_id BIGINT NOT NULL,
  term_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  request_id CHAR(36) NOT NULL UNIQUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (student_id, term_id, course_id),
  FOREIGN KEY (student_id, term_id) REFERENCES student_term_state(student_id, term_id),
  FOREIGN KEY (course_id, term_id) REFERENCES course(id, term_id)
);
CREATE TABLE outbox_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id CHAR(36) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  message_key VARCHAR(80) NOT NULL,
  payload JSON NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'NEW',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  lease_until TIMESTAMP(6) NULL,
  claim_token CHAR(36) NULL,
  last_error VARCHAR(500) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_outbox_event (request_id, kind),
  INDEX ix_outbox_delivery (state, next_attempt_at, lease_until)
);
