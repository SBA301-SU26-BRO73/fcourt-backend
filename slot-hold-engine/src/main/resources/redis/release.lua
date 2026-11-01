for i = 1, #KEYS - 1 do
    if redis.call('get', KEYS[i]) == ARGV[1] then
        redis.call('del', KEYS[i])
    end
end
return redis.call('del', KEYS[#KEYS])
