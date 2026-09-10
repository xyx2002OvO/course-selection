-- A per-student and a global limit share the term slot.
local a = redis.call('INCR', KEYS[1])
if a == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
local b = redis.call('INCR', KEYS[2])
if b == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[3]) end
if a > tonumber(ARGV[1]) or b > tonumber(ARGV[2]) then return 0 end
return 1
