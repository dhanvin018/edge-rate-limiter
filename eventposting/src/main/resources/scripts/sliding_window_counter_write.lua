local curr_key = KEYS[1]
local cost = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])

-- Increment and set TTL (2x window size to allow prev_key lookup in next window)
redis.call('INCRBY', curr_key, cost)
redis.call('PEXPIRE', curr_key, window_ms * 2)

return 1