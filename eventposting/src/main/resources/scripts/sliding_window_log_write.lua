local key = KEYS[1]
local window_ms = tonumber(ARGV[1])
local now = tonumber(ARGV[2])
local member_id = ARGV[3]

local clear_before = now - window_ms

-- 1. Remove elements older than the active rolling window
redis.call('ZREMRANGEBYSCORE', key, '-inf', clear_before)

-- 2. Insert unique member into ZSET
redis.call('ZADD', key, now, member_id)

-- 3. Set TTL
redis.call('PEXPIRE', key, window_ms)

return 1