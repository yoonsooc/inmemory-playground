-- 연결이 끊긴 서버가 호출하는 "삭제" 스크립트. (ADR-0006)
-- 기록된 epoch이 내 연결의 epoch과 같을 때만 지운다.
-- 디바이스가 이미 다시 연결했다면(다른 서버든 같은 서버든) epoch이 달라져 있으므로 건드리지 않는다.
-- 비교와 삭제 사이에 다른 쓰기가 끼어들 수 없도록 한 스크립트 안에서 같이 한다.
--
-- KEYS[1] = device:{id}
-- ARGV[1] = 내 연결의 epoch
--
-- 반환: 1 = 지웠다, 0 = 내 기록이 아니거나 이미 없다
if redis.call('HGET', KEYS[1], 'epoch') == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
