-- HSET과 EXPIRE 사이에 프로세스가 죽으면 TTL 없는 키가 남으므로 한 스크립트로 묶는다.
-- KEYS[1] = device:{id}
-- ARGV[1] = ttl(초)
-- ARGV[2..] = field, value, field, value, ...   (필드가 늘어도 스크립트는 그대로)
redis.call('HSET', KEYS[1], unpack(ARGV, 2))
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
return 1
