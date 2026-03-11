-- KEYS[1]: stock key
-- KEYS[2]: order user set key
-- ARGV[1]: user id

local stock = tonumber(redis.call('GET', KEYS[1]))
if not stock or stock <= 0 then
    return 1
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 0
