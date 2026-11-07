-- Once booking has sealed this capability, only the internal release worker may
-- release it. This check and deletion are atomic against online claim.
if redis.call('exists', ARGV[2]) == 1 then return 0 end
for i = 1, #KEYS - 1 do
    if redis.call('get', KEYS[i]) == ARGV[1] then
        redis.call('del', KEYS[i])
    end
end
return redis.call('del', KEYS[#KEYS])
