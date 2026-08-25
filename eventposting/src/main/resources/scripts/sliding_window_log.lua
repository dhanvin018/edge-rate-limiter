-- KEYS[1]: "rl:global:*:SLIDING_WINDOW_LOG:PT1M"
-- ARGV[1]: Capacity Limit (e.g., 150)
-- ARGV[2]: Window size in ms (e.g., 60000)
-- ARGV[3]: Current timestamp in ms (now)
-- ARGV[4]: Unique Member ID (uniqueMemberId)

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local member_id = ARGV[4]

local clear_before = now - window_ms

-- 1. Remove elements older than the active rolling window
redis.call('ZREMRANGEBYSCORE', key, '-inf', clear_before)

-- 2. Count existing entries
local current_requests = redis.call('ZCARD', key)

if current_requests >= limit then
    return 0 -- Denied (429)
end

-- 3. Insert unique member into ZSET
redis.call('ZADD', key, now, member_id)

-- 4. Set TTL
redis.call('PEXPIRE', key, window_ms)

return 1 -- Allowed (200)