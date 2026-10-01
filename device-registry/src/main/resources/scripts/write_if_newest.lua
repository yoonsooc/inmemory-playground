-- 연결 직후의 등록과 ping 때의 갱신이 함께 쓰는 "쓰기" 스크립트. (ADR-0006)
-- 내 연결보다 나중에 맺어진 연결이 이미 기록되어 있으면 쓰지 않고 0을 돌려준다.
--
-- KEYS[1] = device:{id}
-- ARGV[1] = ttl(초)
-- ARGV[2] = 내 연결의 epoch (연결을 받은 시각, 밀리초)
-- ARGV[3..] = field, value, field, value, ...  (server_id, status, last_seen 등. epoch은 넣지 않는다)
--
-- 반환: 1 = 썼다, 0 = 거부 (나보다 새로운 연결이 있다. 호출한 서버는 자기 emitter를 닫아야 한다)
local stored = redis.call('HGET', KEYS[1], 'epoch')   -- 키나 필드가 없으면 false
if stored and tonumber(stored) > tonumber(ARGV[2]) then
  return 0
end

-- HSET과 EXPIRE 사이에 프로세스가 죽으면 TTL 없는 키가 남으므로 한 스크립트 안에서 같이 한다.
redis.call('HSET', KEYS[1], 'epoch', ARGV[2], unpack(ARGV, 3))
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
return 1
