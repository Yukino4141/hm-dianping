-- KEYS[1]: stock key
-- KEYS[2]: order user set key
-- ARGV[1]: user id

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0 then
    return 0
end

redis.call('SREM', KEYS[2], ARGV[1])
redis.call('INCR', KEYS[1])
return 1
