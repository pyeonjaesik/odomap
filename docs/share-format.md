# QR 공유 형식 v1

서버 없이 차 → 휴대폰으로 공유 카드를 넘기는 방법. 데이터는 QR 코드 안의 URL 조각(`#` 뒤)에 그대로 들어 있다.
브라우저는 `#` 뒤를 서버로 보내지 않으므로, 정적 페이지(GitHub Pages)는 뷰어 파일만 내려주고 데이터는 보지 못한다.

```
https://pyeonjaesik.github.io/odomap/s/#<PAYLOAD>
```

- `<PAYLOAD>` = 이진 데이터(아래)를 **RFC 4648 base32 대문자, 패딩(`=`) 없이** 쓴 것.
- QR은 두 조각으로 만든다: 앞부분 URL은 byte 모드, `<PAYLOAD>`는 alphanumeric 모드(대문자·숫자만이라 5.5bit/글자 → base64 byte 모드보다 약 17% 작다).
- 오류 정정 M, 버전은 최대 20(97×97 모듈). 안 들어가면 단순화 허용 오차를 키워 다시 만든다(아래 '크기 맞추기').

## 이진 데이터

정수는 모두 부호 없는 LEB128 varint(`uv`), 부호 있는 값은 zigzag 후 varint(`sv`). 문자열 `str` = `uv` 바이트 길이 + UTF-8.

```
u8   version            = 1
u8   kind               1 드라이브 카드 · 2 내 지도(누적) · 3 정복 지도(지역만)
u8   flags              bit0 출발·도착 가림 · bit1 자주 가는 곳(집·회사) 주변 가림
uv   dateDays           2020-01-01부터 날 수 (한국 시간 날짜). kind 2·3은 만든 날
uv   startMin           kind 1: 출발 시각(자정부터 분). 그 밖 0
uv   durationS          kind 1: 주행 시간(초). 그 밖 0
uv   distance10         kind 1: 이번 거리 · kind 2·3: 누적 거리 (10m 단위)
uv   pioneered10        kind 1: 이번 처음 달린 길 · kind 2·3: 누적 처음 달린 길(= 내 지도 길이) (10m 단위)
uv   driveCount         kind 1: 0 · kind 2·3: 누적 드라이브 수
str  title              ≤ 48바이트. 예 "강릉 여행", "출근 10/26". 비우면 뷰어가 날짜로 만든다
u8   regionSet          지역 데이터 번호. 1 = sgis-20261001 (230곳, regions.json 의 regions 배열 순서 = 번호)
uv   visitedTotal       지금까지 가 본 지역 수 (카드의 "230곳 중 N곳")
--- kind 1
uv   n  + n × u8        이번에 처음 간 지역 번호
uv   m  + m × u8        이번에 지나간 지역 번호(처음 간 곳 포함, 지난 차례)
--- kind 3
29 바이트               가 본 지역 비트맵 (번호 i → byte i/8, bit i%8 (LSB 먼저))
--- kind 1·2: 선
u8   quantExp           좌표 단위 = 10^-quantExp 도 (4 → 약 11m, 3 → 약 110m)
uv   lineCount
  선마다:
  u8   style            bit0..2 굵기 단계 1–5 · bit3 처음 달린 길(새 길) · 나머지 0
  uv   pointCount       ≥ 2
  sv   dLat, sv dLng    × pointCount — 앞 점과의 차(단위 = 좌표 단위). 첫 선의 첫 점은 (0, 0)과의 차 = 절댓값,
                        다음 선의 첫 점은 앞 선의 마지막 점과의 차
```

- 위도·경도 차례(위도 먼저). 좌표 = round(도 × 10^quantExp).
- 남는 바이트가 있으면 뷰어는 무시한다(뒤에 필드를 더할 때 version을 올리지 않아도 되게).
- 모르는 version/kind/regionSet이면 뷰어는 "앱을 업데이트해 주세요" 안내.

### kind 1 — 드라이브 카드
- 이번 드라이브 길을 처음 달린 구간/아는 구간으로 나눈 선들. 아는 구간의 굵기 = 그 길을 지난 횟수 단계(이번 포함).
- 기본으로 출발·도착에서 길을 따라 300m씩 잘라낸다(flags bit0). 집·회사가 드러나지 않게.

### kind 2 — 내 지도
- 누적 그물 선(`exploreLines(smooth=true)`)을 굵기 단계와 함께. 선이 많으면 짧은 선부터 뺀다(아래).
- 기본으로 자주 가는 곳 주변 500m 안의 선을 뺀다(flags bit1). 자주 가는 곳 = 드라이브 출발·도착점을 300m로 묶은 무리 중 많은 것 둘.

### kind 3 — 정복 지도
- 선 없이 지역 비트맵만. QR이 가장 작다.

## 크기 맞추기
1. 선을 지역 투영(등장방형, 위도 중심 cos 보정) 미터 좌표로 바꿔 Douglas–Peucker 단순화(허용 오차 t).
2. quantExp: 전체 범위가 40km 안이면 4, 아니면 3. (뷰어 화면 1080px에서 110m는 범위 400km일 때 0.3px — 안 보인다)
3. 만들어 본 QR 버전이 20을 넘으면 t를 키운다(이분 탐색 2m ~ 2km). kind 2는 t를 키워도 넘으면 길이가 짧은 선부터 뺀다.
4. 결과가 2점 미만인 선은 뺀다.

## 확인용 벡터
`core/src/test/resources/share/vectors.json` — Kotlin 인코더가 만든 (입력 요약, payload 문자열, 기대 디코드 결과). 웹 뷰어 테스트가 같은 파일을 읽어 디코드 결과를 비교한다.
