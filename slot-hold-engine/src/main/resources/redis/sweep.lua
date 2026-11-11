-- Announce holds whose TTL has passed. Atomic: with several engine instances each due hold is
-- removed from the index and announced exactly once. Only courtId/date/type are published.
local t = redis.call('time')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
local due = redis.call('zrangebyscore', KEYS[1], '-inf', now, 'LIMIT', 0, tonumber(ARGV[1]))
local seen = {}
for _, member in ipairs(due) do
    redis.call('zrem', KEYS[1], member)
    local court, date = string.match(member, '^(%d+)|(%d%d%d%d%-%d%d%-%d%d)|')
    if court and not seen[court .. '|' .. date] then
        seen[court .. '|' .. date] = true
        redis.call('publish', 'fcourt:slot-changes',
            cjson.encode({courtId = tonumber(court), date = date, type = 'HOLD_EXPIRED'}))
    end
end
return #due
