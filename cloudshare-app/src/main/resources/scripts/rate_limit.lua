local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local clear_before = now - window

-- Remove requests outside the sliding window
redis.call('ZREMRANGEBYSCORE', key, '-inf', clear_before)

-- Count total requests in the window
local current_requests = redis.call('ZCARD', key)

if current_requests < limit then
    local seq_key = key .. ':seq'
    local seq = redis.call('INCR', seq_key)
    redis.call('EXPIRE', seq_key, window)
    
    -- Add the current request timestamp with sequence suffix as member
    redis.call('ZADD', key, now, now .. '-' .. seq)
    redis.call('EXPIRE', key, window)
    return 1
else
    return 0
end
