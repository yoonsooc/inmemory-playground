# 명령 라우팅 검증 (2026-10-02 03:30 ~ 03:31)

PRD 체크리스트 4번, 실험 4. 구성은 이전 검증과 같다: app-1(8081) + app-2(8082) + nginx(8080) + 시뮬레이터 + Valkey 클러스터.
코드는 브랜치 `feat/command-routing-pubsub`: `DeviceCommandService`, `CommandSubscriberConfig`, `POST /devices/{id}/command`.

| 확인한 것 | 결과 |
|---|---|
| 연결을 쥐지 않은 서버에 POST → 기기가 받음 | 통과. 발행부터 전달까지 약 20ms |
| 연결을 쥔 서버에 POST → 채널을 거치지 않고 바로 보냄 | 통과 |
| nginx를 거쳐 POST (어느 서버에 갈지 모름) | 통과. 4건 모두 도착 |
| 연결한 적 없는 기기 | 404 OFFLINE |
| 서버가 죽은 직후의 명령 | 202 PUBLISHED를 받지만 사라짐 (알려진 한계) |
| 서버가 재시작한 뒤, 기록은 남았는데 연결이 없는 경우 | 409 NOT_CONNECTED, 경고 로그 |
| TTL 만료 후 | 404 OFFLINE |

## 1. 어느 서버에 보내도 기기에 도착한다

배치: dev-1 = app-1, dev-2 = app-1, dev-3 = app-2.

```
$ curl -X POST localhost:8082/devices/dev-1/command -d '{"action":"lock","n":"dev-1-via-other"}'   # 연결을 쥐지 않은 서버
202 {"outcome":"PUBLISHED","serverId":"app-1"}

$ curl -X POST localhost:8081/devices/dev-1/command -d '{"action":"lock","n":"dev-1-via-owner"}'   # 연결을 쥔 서버
202 {"outcome":"DELIVERED","serverId":"app-1"}
```

```
03:30:13.231  app-2: Command published: id=dev-1, to=app-1
03:30:13.250  app-1: Command delivered from channel: id=dev-1           ← 19ms 뒤
03:30:13.250  [dev-1] event=command data={"action":"lock","n":"dev-1-via-other"}
03:30:13.256  app-1: Command delivered locally: id=dev-1
03:30:13.257  [dev-1] event=command data={"action":"lock","n":"dev-1-via-owner"}
```

dev-2(app-1), dev-3(app-2)도 같은 방식으로 양쪽 서버에 보내 각각 2건씩 모두 받았다.

## 2. nginx를 거쳐 보내기

보내는 쪽은 서버가 몇 대인지, 기기가 어디 붙어 있는지 모른다. nginx가 번갈아 보내므로 결과가 번갈아 나온다.

```
$ for i in 1 2 3 4; do curl -X POST localhost:8080/devices/dev-1/command -d "via-nginx-$i"; done
202 {"outcome":"PUBLISHED","serverId":"app-1"}     ← app-2가 받아서 발행
202 {"outcome":"DELIVERED","serverId":"app-1"}     ← app-1이 받아서 바로 보냄
202 {"outcome":"PUBLISHED","serverId":"app-1"}
202 {"outcome":"DELIVERED","serverId":"app-1"}

03:30:13.408  [dev-1] event=command data=via-nginx-1
03:30:13.425  [dev-1] event=command data=via-nginx-2
03:30:13.451  [dev-1] event=command data=via-nginx-3
03:30:13.470  [dev-1] event=command data=via-nginx-4
```

## 3. 오프라인 기기

```
$ curl -X POST localhost:8082/devices/dev-404/command -d hello
404 {"outcome":"OFFLINE","serverId":null}

03:30:13.490  app-2: Command dropped, device is offline: id=dev-404
```

## 4. 서버가 죽은 직후와 재시작한 뒤

dev-1이 app-1에 있는 상태에서 app-1과 시뮬레이터를 동시에 kill -9 했다. 기록(`server_id=app-1`)은 TTL이 지날 때까지 남는다.

```
03:30:44  app-1 + 시뮬레이터 kill -9          (기록: server_id=app-1, ttl=25)

+1s   app-2에 POST → 202 {"outcome":"PUBLISHED","serverId":"app-1"}
      03:30:45.281  app-2: Command published: id=dev-1, to=app-1
      듣는 서버가 없어서 이 명령은 어디에도 도착하지 않는다                ← 알려진 한계

+4s   app-1 재기동 완료                       (기록: server_id=app-1, ttl=20. 맵은 비어 있음)

+4s   재기동한 app-1에 POST → 409 {"outcome":"NOT_CONNECTED","serverId":"app-1"}
      03:30:49.002  app-1: WARN Command dropped, record points here but no local connection: id=dev-1

+5s   app-2에 POST → 202 {"outcome":"PUBLISHED","serverId":"app-1"}
      03:30:49.049  app-1: WARN Command dropped, published here but no local connection: id=dev-1

+25s  TTL 만료 후 app-2에 POST → 404 {"outcome":"OFFLINE","serverId":null}
```

읽는 법:

- **+1s의 명령은 조용히 사라진다.** 보낸 쪽은 202를 받는다. 죽은 서버의 기록이 남아 있는 동안(최대 TTL 30초)의 한계이고, 응답의 `PUBLISHED`가 "기기가 받았다"가 아니라 "발행했다"는 뜻인 이유다.
- **+4s와 +5s는 서버가 살아 있어서 어긋남이 로그로 드러난다.** 같은 상황을 두 방향에서 본 것이다. 직접 받은 서버는 409로 알려 주고, 채널로 받은 서버는 경고 로그만 남긴다(보낸 쪽은 이미 202를 받은 뒤다).
- 기기가 다시 연결하면 그 순간 기록이 새 서버로 바뀌므로 이 구간은 바로 끝난다. 이 실험은 기기도 함께 죽여서 구간을 일부러 길게 만든 것이다.

## 5. 리뷰 후 재확인 (2026-10-03 00:2x)

메서드 이름을 정리한 뒤(`commandDevice`, `trySendMessage`, `DeviceCommandService.command`) 같은 실험을 다시 돌렸다.

```
배치: dev-1=app-1 dev-2=app-2 dev-3=app-1. 두 서버의 조회 응답 동일
기기 3대 × 보내는 곳 3가지(8081, 8082, nginx 8080) = 9건 → 기기가 받은 명령 9건
없는 기기 → 404 {"outcome":"OFFLINE","serverId":null}
```

서버가 죽고 기기가 다른 서버로 옮겨간 뒤에도 명령이 따라가는지도 확인했다.

```
app-1 kill -9 (dev-1이 app-1에 있었음)
3초 뒤 dev-1 기록: server_id=app-2                      ← 기기가 재연결하면서 기록이 바뀜
app-2에 POST → 202 {"outcome":"DELIVERED","serverId":"app-2"}
[dev-1] event=command data=after-failover
```

4번의 유실 구간은 기기가 재연결하지 못할 때만 TTL까지 이어진다. 기기가 재연결하면(실험 2에서 1초) 그 순간부터 명령은 새 서버로 간다.
