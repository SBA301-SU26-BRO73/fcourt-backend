for i = 1, #KEYS - 1 do
    if redis.call('get', KEYS[i]) == ARGV[1] then
        redis.call('del', KEYS[i])
    end
end
local payload = redis.call('get', KEYS[#KEYS])
local removed = redis.call('del', KEYS[#KEYS])
if removed == 1 and payload then
    local request = cjson.decode(payload)
    redis.call('publish', 'fcourt:slot-changes', cjson.encode({courtId=request.courtId,date=request.date}))
end
return removed
