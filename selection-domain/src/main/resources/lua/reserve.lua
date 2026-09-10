-- All keys use the same term hash tag. The reservation ledger has no short TTL.
-- KEYS: stock, student lease, ledger, status, due index, short dedup, ready
-- ARGV: id, student, course, term, lease ms, dedup seconds, status seconds
if redis.call('EXISTS', KEYS[6]) == 1 then
    if redis.call('HGET', KEYS[6], 'studentId') ~= ARGV[2]
        or redis.call('HGET', KEYS[6], 'courseId') ~= ARGV[3] then return 'KEY_REUSED' end
    if redis.call('EXISTS', KEYS[3]) == 1 then return 'EXISTING' end
    redis.call('SET', KEYS[7], 'blocked')
    return 'NOT_READY'
end
if redis.call('EXISTS', KEYS[3]) == 1 then
    if redis.call('HGET', KEYS[3], 'studentId') ~= ARGV[2]
        or redis.call('HGET', KEYS[3], 'courseId') ~= ARGV[3] then return 'KEY_REUSED' end
    return 'EXISTING'
end
if redis.call('GET', KEYS[7]) ~= 'ready' then return 'NOT_READY' end
local available = tonumber(redis.call('GET', KEYS[1]))
if available == nil then return 'NOT_READY' end
if redis.call('EXISTS', KEYS[2]) == 1 then return 'STUDENT_BUSY' end
if available <= 0 then return 'SOLD_OUT' end
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local deadline = now + tonumber(ARGV[5])
redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[5])
redis.call('DECR', KEYS[1])
redis.call('HSET', KEYS[3], 'requestId', ARGV[1], 'studentId', ARGV[2],
    'courseId', ARGV[3], 'termId', ARGV[4], 'deadline', tostring(deadline),
    'state', 'PROCESSING', 'reason', '', 'settled', '0')
redis.call('HSET', KEYS[4], 'studentId', ARGV[2], 'state', 'PROCESSING', 'reason', '')
redis.call('EXPIRE', KEYS[4], tonumber(ARGV[7]) + math.random(0, tonumber(ARGV[8])))
redis.call('HSET', KEYS[6], 'studentId', ARGV[2], 'courseId', ARGV[3])
redis.call('EXPIRE', KEYS[6], ARGV[6])
redis.call('ZADD', KEYS[5], deadline, ARGV[1])
return 'RESERVED'
