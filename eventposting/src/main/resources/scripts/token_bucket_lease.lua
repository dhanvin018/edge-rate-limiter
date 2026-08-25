-- KEYS[1]: Rate limit key
-- ARGV[1]: Capacity (effective capacity / max burst)
-- ARGV[2]: Refill rate per millisecond (double)
-- ARGV[3]: Requested lease batch size (integer)
-- ARGV[4]: Current timestamp in milliseconds (from application clock)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRatePerMs = tonumber(ARGV[2])
local requestedBatch = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

-- Read bucket state from Redis
local data = redis.call('HMGET', key, 'tokens', 'lastRefill')
local tokens = tonumber(data[1])
local lastRefill = tonumber(data[2])

if not tokens then
    -- Initial state: Start full
    tokens = capacity
    lastRefill = now
else
    -- Calculate continuous refill based on elapsed time from application clock
    local elapsed = math.max(0, now - lastRefill)
    local refilled = elapsed * refillRatePerMs
    tokens = math.min(capacity, tokens + refilled)

    -- Update lastRefill timestamp ONLY if elapsed time actually moved forward
    if elapsed > 0 then
        lastRefill = now
    end
end

-- Determine how many whole tokens can be granted
local availableIntegerTokens = math.floor(tokens)
local granted = 0

if availableIntegerTokens >= requestedBatch then
    granted = requestedBatch
    tokens = tokens - requestedBatch
elseif availableIntegerTokens > 0 then
    -- Grant max available integer tokens
    granted = availableIntegerTokens
    tokens = tokens - granted
end

-- Save state
redis.call('HMSET', key, 'tokens', tokens, 'lastRefill', lastRefill)
redis.call('PEXPIRE', key, 3600000)

return granted