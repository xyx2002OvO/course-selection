-- A delayed cache repair must not overwrite an already projected terminal result.
local old = redis.call('HGET', KEYS[1], 'state')
if old and ARGV[2] == 'NOT_FOUND' then return 0 end
if old == 'SUCCESS' or old == 'REJECTED' or old == 'CANCELLED' then return 0 end
redis.call('HSET', KEYS[1], 'studentId', ARGV[1], 'state', ARGV[2], 'reason', ARGV[3])
redis.call('EXPIRE', KEYS[1], ARGV[4])
return 1
