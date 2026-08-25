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

return 1 -- Allow