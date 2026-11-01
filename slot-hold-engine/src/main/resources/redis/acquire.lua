-- Check the whole selection before writing: conflict never leaves partial holds.
for i = 1, #KEYS do
    if redis.call('exists', KEYS[i]) == 1 then
        return 0
    end
end
local now = redis.call('time')
local expires = now[1] * 1000 + math.floor(now[2] / 1000) + tonumber(ARGV[2]) * 1000
for i = 1, #KEYS - 1 do
    redis.call('set', KEYS[i], ARGV[1], 'PXAT', expires)
end
redis.call('set', KEYS[#KEYS], ARGV[3], 'PXAT', expires)
return expires
