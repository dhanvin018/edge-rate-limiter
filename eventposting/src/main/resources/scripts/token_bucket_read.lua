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
    tokens = capacity
else
    local elapsed = math.max(0, now - lastRefill)
    local refilled = elapsed * refillRatePerMs
    tokens = math.min(capacity, tokens + refilled)
end

local availableIntegerTokens = math.floor(tokens)

-- Read-only check: Return 1 if at least 1 token is available to grant, 0 otherwise
if availableIntegerTokens > 0 then
    return 1
else
    return 0
end