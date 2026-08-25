local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local clear_before = now - window_ms

-- Count remaining entries in active window frame using ZCOUNT (read-only)
local current_requests = redis.call('ZCOUNT', key, clear_before, '+inf')

if current_requests >= limit then
    return 0 -- Denied
end

return 1 -- Allowed