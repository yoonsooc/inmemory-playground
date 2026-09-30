-- 연결이 끊긴 서버가 호출. server_id가 아직 자기 자신일 때만 지운다.
-- 디바이스가 이미 다른 서버로 재연결했다면 server_id가 바뀌어 있으므로 건드리지 않는다.
--
-- 비교와 삭제 사이에 다른 서버의 등록이 끼어들 수 없도록 한 스크립트로 묶는다.
--
-- KEYS[1] = device:{id}
-- ARGV[1] = 이 서버의 server_id
if redis.call('HGET', KEYS[1], 'server_id') == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
