package dev.yoonsooc.device_registry.data;

/**
 * 명령 POST의 결과.
 *
 * @param outcome  어떻게 처리됐는가
 * @param serverId 기록에 적힌 연결 주인 서버. 오프라인이면 null
 */
public record CommandResult(Outcome outcome, String serverId) {

    public enum Outcome {
        /** 이 서버가 연결을 쥐고 있어서 SSE로 바로 보냈다. */
        DELIVERED,
        /** 다른 서버가 연결을 쥐고 있어서 그 서버의 채널에 발행했다. 기기가 받았는지는 확인하지 않는다. */
        PUBLISHED,
        /** 기록이 없다. 기기가 연결되어 있지 않다. */
        OFFLINE,
        /** 기록은 이 서버를 가리키는데 이 서버에 그 연결이 없다. 서버 재시작 직후 같은 경우. */
        NOT_CONNECTED
    }
}
