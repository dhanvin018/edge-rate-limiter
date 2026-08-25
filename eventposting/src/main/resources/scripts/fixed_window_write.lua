local key = KEYS[1]
local cost = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])

-- Atomically increment counter
local updated = redis.call('INCRBY', key, cost)

-- Set expiration when key is first created
if updated == cost then
    -- TTL set slightly longer than window duration to prevent premature eviction
    redis.call('PEXPIRE', key, windowMs + 1000)
end

return 1