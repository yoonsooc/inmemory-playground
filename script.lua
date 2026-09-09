local lock_key = KEYS[1]
local data_key = KEYS[2]
local my_token = ARGV[1]

local owner = redis.call('GET', lock_key)
if owner ~= my_token then
    return nil
end
return redis.call('GET', data_key)
