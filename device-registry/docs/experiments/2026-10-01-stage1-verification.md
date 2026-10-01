# 1단계 기능 검증 (2026-10-01 02:56 ~ 03:01)

구성: app-1(8081) + app-2(8082) + nginx(8080, 라운드로빈) + 시뮬레이터(nginx 경유) + Valkey 클러스터(VPN 경유).
설정: TTL 30s, ping 10s. 커밋 `63cc75e` 기준. nginx 설정에 `proxy_set_header Host $host` 한 줄이 추가됐다(아래 참고).

전체 로그: 스크래치패드 `exp/` (app-1.log, app-1b.log, app-2.log, sim*.log). 세션이 끝나면 사라지므로 필요한 부분은 여기에 옮겼다.

| 실험 | 결과 | 핵심 수치 |
|---|---|---|
| 1. 두 서버에서 조회 결과 동일 | 통과 | 응답 바이트 단위 동일 |
| 3. 기기 kill -9 → 서버가 감지·정리 | 통과 | 17초 (ping 2회째에 실패) |
| 2. 서버 kill -9 → 다른 서버로 재연결 | 통과 | 1초 만에 app-2로, server_id 즉시 전환 |
| 5. 서버 재기동 → 다시 연결 받음 | 통과 | 재기동 3초 후 health UP, 라운드로빈 복귀 |
| 2b. 서버+기기 동시 사망 (재연결 없음) | 통과 | ping 실패 정리 16초, TTL 만료 정리 24초(마지막 갱신 후 30초) |
| (발견) 좀비 연결의 갱신이 server_id를 덮어씀 | **결함** | 최대 ping 2회(~20초) 동안 잘못된 server_id. backlog 0008 |

## 실험 1. 어느 서버에 물어도 같은 답

```
02:57:35.944 [dev-1] connected to server=app-1
02:57:35.944 [dev-2] connected to server=app-2
02:57:35.944 [dev-3] connected to server=app-1
02:57:40.766 [dev-1] ping from app-1        ← 첫 ping. 이때 last_seen·TTL 갱신됨

$ curl localhost:8081/devices   # app-1
$ curl localhost:8082/devices   # app-2
→ 두 응답 동일 (diff 없음). dev-1=app-1, dev-2=app-2, dev-3=app-1, 전부 ONLINE

$ valkey-cli hgetall device:dev-1 → server_id app-1, status ONLINE, last_seen 17:57:40.765Z   ttl=21
$ valkey-cli smembers devices:known → dev-1 dev-2 dev-3
```

## 실험 3. 기기 kill -9 (서버는 살아 있음)

```
02:58:04  시뮬레이터 kill -9
02:58:11  app-1: Lease renewed: id=dev-3          ← 죽은 연결에 대한 ping 쓰기가 "성공"
02:58:21  app-1: SSE send failed, closing: id=dev-1, event=ping (AsyncRequestNotUsableException)
02:58:21  app-1: SSE disconnected: id=dev-1, reason=completed
02:58:21  app-1: Device unregistered: id=dev-1, server=app-1
02:58:21  app-2: Device unregistered: id=dev-2, server=app-2
02:58:21  3대 모두 OFFLINE (kill 후 17초). valkey device:* 키 0개
```

TCP 쓰기는 상대 수신 확인 없이 성공으로 돌아온다(ADR-0003 "잃는 것"). 그래서 첫 ping은 통과하고 두 번째 ping에서 실패했다. 감지 지연은 ping 주기의 1~2배다.

## 실험 2. 서버 kill -9 → 다른 서버로 재연결

```
배치: dev-1=app-2, dev-2=app-1, dev-3=app-2
02:58:48  app-1 kill -9
02:58:48  [dev-2] SSE closed: ConnectionError: server closed the stream / reconnecting in 1s
02:58:49  nginx: connect() failed (61: Connection refused) upstream 8081 → upstream server temporarily disabled
02:58:49  [dev-2] connected to server=app-2
02:58:49  app-2: Device registered: id=dev-2, server=app-2, new=false
02:58:49  3대 모두 app-2 ONLINE (kill 후 1초)
```

죽은 서버의 unregister는 실행되지 못하지만, 재연결한 서버의 register가 server_id를 덮어쓰므로 TTL을 기다리지 않는다.

## 실험 5. app-1 재기동

```
02:59:07  app-1 재기동 → 3초 후 health UP
02:59:12  시뮬레이터 4대 재시작. nginx 라운드로빈이 8081을 다시 포함 (curl 4회: 8082, 8081, 8082, 8081)
```

## 실험 2b. 서버 + 기기 동시 kill -9 (아무도 재연결하지 않음)

```
직전: dev-1,2,4=app-2, dev-3=app-1.  dev-3 마지막 갱신 03:00:29.9
03:00:36  app-1 + 시뮬레이터 동시 kill -9
03:00:52  app-2: SSE send failed (dev-1, dev-2, dev-4) → unregistered        ← ping 실패 경로, +16초
03:00:53  조회: dev-1,2,4 OFFLINE, dev-3 ONLINE(app-1)                        ← app-1의 기록은 지울 주체가 없다
03:01:00  조회: 전부 OFFLINE                                                   ← TTL 만료 경로, 마지막 갱신 후 30초 (+24초)
```

## 발견: 좀비 연결의 갱신이 새 연결의 server_id를 덮어쓴다

실험 5 도중 관찰. 시뮬레이터를 kill -9 하고 1초 뒤 새 시뮬레이터를 띄웠다. 옛 dev-3는 app-2에, 새 dev-3는 app-1에 붙었다.

```
02:59:10  옛 시뮬레이터 kill -9 (옛 dev-3 연결은 app-2에 있었음)
02:59:11  app-2: Lease renewed: id=dev-3, server=app-2    ← 좀비 연결에 대한 ping이 "성공", server_id=app-2 기록
02:59:13  app-1: Device registered: id=dev-3, server=app-1 ← 새 연결. server_id=app-1
02:59:19  app-1: Lease renewed: id=dev-3, server=app-1
02:59:21  app-2: Lease renewed: id=dev-3, server=app-2    ← 좀비가 또 "성공". server_id=app-2 로 덮어씀 (잘못된 값)
02:59:23  조회: dev-3 = app-2                              ← 실제 연결은 app-1에 있다
02:59:29  app-1: Lease renewed: id=dev-3, server=app-1    ← 다시 app-1
02:59:32  app-2: SSE send failed → disconnected → "Device record kept (moved or expired)"   ← 조건부 삭제가 app-1 기록을 지킴
```

ADR-0003의 전제 "연결 주인이 갱신하면 거짓이 생기지 않는다"는 "연결 주인이 자기 연결이 죽은 것을 아는 한"이라는 조건이 붙는다. TCP는 그것을 즉시 알려주지 않는다. 그 사이 좀비의 무조건 쓰기(register.lua)가 새 연결의 기록을 덮어쓴다. 이 창에서 명령이 오면 app-2로 라우팅되어 유실된다(4단계). 조건부 삭제는 지켰지만, 좀비가 자기 갱신 직후 실패했다면 `server_id == app-2`이므로 살아 있는 연결의 기록을 지웠을 것이다. 다음 갱신(≤10초)에서 되살아나지만 창은 존재한다. 보완 방향은 backlog 0008.

## nginx 설정 수정

첫 요청이 400으로 실패했다. nginx는 기본으로 upstream 블록 이름(`device_registry`)을 Host 헤더로 넘기는데, Tomcat은 밑줄이 든 호스트명을 거부한다. `proxy_set_header Host $host;`를 추가해 해결했다(작업 트리에 미커밋 상태).
