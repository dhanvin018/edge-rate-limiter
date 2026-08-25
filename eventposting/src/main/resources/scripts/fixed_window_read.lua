local key = KEYS[1]
local limit = tonumber(ARGV[1])
local cost = tonumber(ARGV[2])

-- Get current counter
local current = tonumber(redis.call('GET', key) or "0")

if current + cost > limit then
    return 0 -- Rate limit exceeded
end

return 1 -- Allowed