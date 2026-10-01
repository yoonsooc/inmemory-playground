# ADR-0006(연결 번호 비교) 구현 후 검증 (2026-10-02 01:16 ~ 01:20)

구성: app-1(8081) + app-2(8082) + nginx(8080, 라운드로빈) + 시뮬레이터(nginx 경유) + Valkey 클러스터(VPN 경유).
설정: TTL 30s, ping 10s. 코드는 `9ded548` 위의 작업 트리(미커밋): epoch 비교가 들어간 register.lua / unregister.lua, `Connection(emitter, epoch)` record.
조회 결과는 0.3초 간격으로 지켜보며 바뀔 때만 기록했다.

| 실험 | 결과 | 핵심 수치 |
|---|---|---|
| 1. 두 서버에서 조회 결과 동일 | 통과 | 응답 동일. 해시에 `epoch` 필드 확인 |
| 3. 기기 kill -9 → 서버가 알아채고 지움 | 통과 | 13초. "Device unregistered" |
| 6a. 기기 kill -9 후 1초 안에 재시작 | 통과 | 35초 동안 `server_id`가 옛 서버로 되돌아간 적 없음 |
| 6b. 옛 연결이 살아 있고 기록이 틀린 상태 | 통과 | 옛 서버가 갱신 거부를 받고 스스로 닫음. 기록은 4초 뒤 복구. 새 연결은 유지 |
| 2. 서버 kill -9 → 다른 서버로 재연결 | 통과 | 1초 |
| 5. 서버 재기동 | 통과 | 4초 후 health UP, 새 연결을 다시 받음 |
| 2b. 서버 + 기기 동시 kill -9 | 통과 | ping 실패로 지움 16초, TTL 만료 29초(마지막 갱신 후 30초) |

## 실험 1

```
01:16:20.537 [dev-1] connected to server=app-1
01:16:20.537 [dev-2] connected to server=app-1
01:16:20.528 [dev-3] connected to server=app-2
01:16:25.9   ping (세 대 모두)

두 서버의 GET /devices 응답 동일
device:dev-1 -> epoch 1790871380440 / server_id app-1 / status ONLINE / last_seen 16:16:25.956Z   ttl=22
```

## 실험 3. 기기 kill -9

```
01:16:33  시뮬레이터 kill -9
01:16:46.011  app-1: SSE send failed, closing: id=dev-1, event=ping
01:16:46.033  app-1: SSE disconnected: id=dev-1, reason=completed, local connections=0
01:16:46.049  app-1: Device unregistered: id=dev-1, server=app-1
01:16:45.991  app-2: Device unregistered: id=dev-3, server=app-2
01:16:46  3대 모두 OFFLINE (13초). device:* 키 0개
```

## 실험 6a. 기기 kill -9 후 1초 안에 재시작 (좀비 재현)

10-01 검증에서 `server_id`가 옛 서버로 되돌아갔던 바로 그 조작이다.

```
재시작 전:  dev-1=app-1  dev-2=app-2  dev-3=app-1  dev-4=app-2
01:17:15.638  옛 시뮬레이터 kill -9
01:17:16.497  새 시뮬레이터 연결: dev-1=app-1  dev-2=app-1  dev-3=app-2  dev-4=app-2
              (dev-2와 dev-3가 서버를 바꿈. dev-1과 dev-4는 같은 서버로 다시 붙음)

조회 변화 (0.3초 간격, 35초 동안):
01:17:16.581  dev-1=app-1 dev-2=app-1 dev-3=app-2 dev-4=app-2      ← 이 한 줄뿐. 되돌아간 적 없음

같은 서버로 다시 붙은 경우 (dev-1, app-1):
01:17:16.443  Replacing stale connection: id=dev-1
01:17:16.446  SSE disconnect ignored (already replaced or removed): id=dev-1   ← 옛 연결의 콜백이 새 연결을 건드리지 않음

서버를 바꾼 경우 (dev-3: 옛 연결은 app-1):
01:17:26.087  app-1: SSE send failed, closing: id=dev-3, event=ping
01:17:26.092  app-1: Device record kept (moved or expired): id=dev-3          ← epoch이 달라 삭제하지 않음
```

이번에는 옛 서버가 첫 ping에서 바로 쓰기 실패를 만나서 "갱신 거부" 경로는 타지 않았다. 그 경로는 6b에서 확인했다.

## 실험 6b. 옛 연결이 살아 있는 상태에서 기록이 틀려진 경우

끊기지 않는 옛 연결을 curl로 직접 만들었다. 옛 연결은 app-1, 2초 뒤 새 연결은 app-2. 그 다음 키를 지우고(만료 흉내), 옛 연결이 먼저 쓴 것처럼 `server_id=app-1`, 더 작은 epoch을 직접 써 넣었다. ADR-0006에서 A 방식을 버린 이유였던 순서다.

```
01:18:32.422  기록: server_id=app-2 epoch=1790871511783            (정상)
01:18:32.517  키 DEL 후 옛 연결이 먼저 쓴 상태를 만듦
01:18:32.555  기록: server_id=app-1 epoch=1790871510283            (일부러 틀리게 만든 상태)
01:18:36.278  app-1: SSE disconnected: id=dev-9, reason=completed   ← ping 쓰기는 성공했지만 갱신이 거부되어 스스로 닫음
01:18:36.286  app-1: Device record kept (moved or expired)          ← 삭제도 건너뜀
01:18:36.295  기록: server_id=app-2 epoch=1790871511783            ← app-2의 갱신이 되찾음 (약 4초 뒤)

옛 연결(curl): ping 한 번 받고 01:18:36에 닫힘.  app-1의 dev-9 갱신 성공 횟수: 0
새 연결(curl): 계속 열려 있음.                   app-2의 dev-9 갱신 성공 횟수: 2
```

기록이 틀린 시간은 다음 ping까지(이번에는 4초, 최대 10초)였고, 새 연결은 끊기지 않았다.

## 실험 2. 서버 kill -9

```
01:19:05  app-1 kill -9  (dev-1, dev-2가 app-1에 있었음)
01:19:05.473  [dev-1] SSE closed: server closed the stream / reconnecting in 1s
01:19:06.495  [dev-1] connected to server=app-2
01:19:06  dev-1~4 모두 app-2 (1초)
```

## 실험 5. app-1 재기동

```
01:19:06  재기동 → 4초 후 health UP
시뮬레이터 재시작 후 배치: dev-1=app-2 dev-2=app-2 dev-3=app-2 dev-4=app-1
```

## 실험 2b. 서버 + 기기 동시 kill -9

```
01:19:49.574  dev-4의 마지막 갱신 (app-1). TTL 30
01:19:50      app-1 + 시뮬레이터 동시 kill -9
01:20:06.4    app-2: dev-1, dev-2, dev-3 unregistered          ← ping 실패 경로, 16초
01:20:06.665  조회: dev-1~3 OFFLINE, dev-4=app-1               ← 지울 주체가 없음
01:20:19.671  조회: dev-4 OFFLINE                              ← TTL 만료, 마지막 갱신 후 30초
```

## 이름 정리 후 재확인 (02:13 ~ 02:16)

스크립트를 `write_if_newest.lua` / `delete_if_mine.lua`로, 콜백을 `onConnectionClosed`로 바꾸고 `trySend` / `end` / `pingAndExtend`로 나눈 뒤 같은 실험을 다시 돌렸다. 동작은 그대로다.

```
1)  두 서버 응답 동일. devices:known = dev-1 dev-2 dev-3
3)  기기 kill -9 → 13초 뒤 3대 OFFLINE, 키 0개, 두 서버의 맵 모두 0
6b) 02:14:39.108 app-1: Connection renew failed.           ← 갱신 거부 (문구는 "거부"로 고칠 예정)
    02:14:39.110 app-1: SSE disconnected: id=dev-9, reason=completed
    02:14:39.120 app-1: Device record kept (moved or expired)
    옛 연결은 닫히고 새 연결은 유지, 기록은 app-2
6a) 기기 4대 kill 후 0.7초 뒤 재시작 → 35초 동안 0.5초 간격 조회가 실제 배치와 달랐던 횟수 0
2)  app-1 kill -9 → 1초 뒤 4대 모두 app-2
```

## 남은 것

- 갱신이 거부됐을 때 따로 로그가 없다. 지금은 "SSE disconnected: reason=completed"와 "record kept"로 짐작해야 한다. 한 줄 추가하면 좋다.
- 실험 4(명령 라우팅)는 4단계 구현 후.
