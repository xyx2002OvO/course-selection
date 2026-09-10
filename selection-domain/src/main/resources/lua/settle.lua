-- KEYS: stock, student lease, ledger, status, due index, ready
-- ARGV: id, student, course, final state, reason, status seconds, jitter seconds
if redis.call('EXISTS', KEYS[3]) == 0 then return 'MISSING_LEDGER' end
if redis.call('HGET', KEYS[3], 'studentId') ~= ARGV[2]
    or redis.call('HGET', KEYS[3], 'courseId') ~= ARGV[3] then return 'KEY_REUSED' end
local settled = redis.call('HGET', KEYS[3], 'settled')
if settled == '1' and redis.call('HGET', KEYS[3], 'state') ~= ARGV[4] then return 'STATE_CONFLICT' end
if settled ~= '1' then
    if redis.call('GET', KEYS[6]) ~= 'ready' or redis.call('EXISTS', KEYS[1]) == 0 then
        return 'NOT_READY'
    end
    if ARGV[4] ~= 'SUCCESS' then redis.call('INCR', KEYS[1]) end
    redis.call('HSET', KEYS[3], 'settled', '1', 'state', ARGV[4], 'reason', ARGV[5])
end
redis.call('HSET', KEYS[4], 'studentId', ARGV[2], 'state', ARGV[4], 'reason', ARGV[5])
redis.call('EXPIRE', KEYS[4], tonumber(ARGV[6]) + math.random(0, tonumber(ARGV[7] or '0')))
if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('DEL', KEYS[2]) end
redis.call('ZREM', KEYS[5], ARGV[1])
return 'OK'
