export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

export function studentForIteration(from, to, iteration) {
  const span = to - from + 1;
  return from + (iteration % span);
}

export const env = {
  base: __ENV.BASE_URL || 'http://api:18080',
  studentFrom: Number(__ENV.STUDENT_FROM || 20001),
  studentTo: Number(__ENV.STUDENT_TO || 80000),
  courseOpen: Number(__ENV.COURSE_OPEN || 201),
  courseScarce: Number(__ENV.COURSE_SCARCE || 202),
  courseFrom: Number(__ENV.COURSE_FROM || 211),
  courseTo: Number(__ENV.COURSE_TO || 222),
};

export function courseForIteration(from, to, iteration) {
  const span = to - from + 1;
  return from + (iteration % span);
}
