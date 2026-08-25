-- KEYS[1]: currentKey (e.g., "rl:global:*:SLIDING_WINDOW_COUNTER:PT2S:893706459")
-- KEYS[2]: previousKey (e.g., "rl:global:*:SLIDING_WINDOW_COUNTER:PT2S:893706458")
-- ARGV[1]: Limit (10)
-- ARGV[2]: Window size in ms (2000)
-- ARGV[3]: Current timestamp in ms (1787412918994)
-- ARGV[4]: Cost (1)

local curr_key = KEYS[1]
local prev_key = KEYS[2]

local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])

-- 1. Get current counts
local curr_raw = redis.call('GET', curr_key)
local prev_raw = redis.call('GET', prev_key)

local curr_count = curr_raw and tonumber(curr_raw) or 0
local prev_count = prev_raw and tonumber(prev_raw) or 0

-- 2. Calculate time elapsed in current window frame
local time_into_curr_window = now % window_ms
local weight = (window_ms - time_into_curr_window) / window_ms

-- 3. Calculate weighted estimate
local estimated_count = math.floor(prev_count * weight) + curr_count

if estimated_count + cost > limit then
    return 0 -- Reject
end

-- 4. Increment and set TTL (2x window size to allow prev_key lookup in next window)
redis.call('INCRBY', curr_key, cost)
redis.call('PEXPIRE', curr_key, window_ms * 2)

return 1 -- Allow