# 발자취 디자인 값 (원본 코드에서 뽑음)

출처: `/Users/dmyeon/clientApp` (읽기 전용) — `src/services/explore/exploreLook.ts`, `src/screens/explore/explorePalette.ts`, `src/services/explore/exploreLineStyles.ts`, `src/components/run/map/{NativeRunMap.tsx,paperGrid.ts,realMapStyle.ts}`, `src/components/explore/*`, `growth/growthDraw.ts`, `card/drawExploreCard.ts`, `src/components/motion/*`.
단위 pt = 발자취(휴대폰) 기준. **Odomap은 이 pt 값을 그대로 쓰고 화면 밀도 × 1.75 로 그린다**(design.md 1절). 줌은 "구글 줌"(= MapLibre 줌 + 1)과 MapLibre 줌을 구분해 적었다.

**코드가 문서·옛 시안보다 맞다.** 옛 시안(10-07~08)에 있는 것 중 지금은 없는 것: 숫자 굵기 700 일괄, 판 머리 줄 '오늘 · 내 발자취 29.87km ›', 도구 묶음 안 꾸미기·▶, 파란 '+km', 새 길 파란 선, '새로 걸은 길'(지금 '처음 걸은 길').

## 1. 색

### 1.1 배색 17 (`EXPLORE_PAPERS`, 순서 그대로)

| id | 이름 | scheme | paper | ink | fresh | text | textSoft | button | buttonIcon | halo |
|---|---|---|---|---|---|---|---|---|---|---|
| paper | 종이 | light | #F7F7F5 | #262626 | #1A73E8 | #1C1C1E | #8A8A8E | #FFFFFF | #1C1C1E | #4A90E2 |
| hanji | 한지 | light | #F4F0E6 | #2A2622 | #2D4B9A | #24201C | #8C847A | #FFFDF8 | #24201C | #4A64A8 |
| news | 신문 | light | #ECEBE7 | #141414 | #1F4FD1 | #111111 | #7C7A75 | #FFFFFF | #111111 | #3C64D6 |
| sand | 모래 | light | #F1EADC | #4B3F31 | #B4651E | #2F271E | #9A8E7E | #FFFCF6 | #2F271E | #C0884F |
| kraft | 크라프트 | light | #E3D3B8 | #2F2416 | #2156A8 | #2A2014 | #7D6B52 | #FBF4E8 | #2A2014 | #2F6DB5 |
| lemon | 레몬 | light | #FAF6E0 | #3C3820 | #B88A00 | #2B2812 | #8F8A6E | #FFFFFF | #2B2812 | #D4A82A |
| apricot | 살구 | light | #FBF0E8 | #5B3A2B | #E0703A | #3F2418 | #A08778 | #FFFFFF | #3F2418 | #E8875A |
| blossom | 벚꽃 | light | #F8EFEF | #5C2C36 | #D04F6B | #3D1E25 | #9A8186 | #FFFFFF | #3D1E25 | #D96A82 |
| lilac | 라일락 | light | #F0EFF7 | #34305B | #6C5CE7 | #24213F | #8A87A3 | #FFFFFF | #24213F | #7B6EE8 |
| sky | 하늘 | light | #EDF2F8 | #1F3B57 | #2E7BD6 | #16293D | #7F8EA0 | #FFFFFF | #16293D | #3D86D9 |
| sea | 바다 | light | #EAF4F3 | #134E4A | #0E9F96 | #0F3532 | #75908D | #FFFFFF | #0F3532 | #1BA39C |
| mint | 쑥 | light | #EEF4EF | #21473A | #2E8B57 | #17322A | #7B8F86 | #FFFFFF | #17322A | #3C9A6A |
| night | 밤 | dark | #141416 | #ECEAE5 | #8AB4F8 | #F2F2F0 | #8E8E93 | #26262A | #F2F2F0 | #8AB4F8 |
| dawn | 새벽 | dark | #1C1A2E | #E8E4FF | #FF9EC4 | #EEEAFF | #9C97B8 | #2A2742 | #EEEAFF | #B9A7FF |
| blueprint | 청사진 | dark | #173A63 | #EAF2FB | #7FD1FF | #EEF4FB | #9DB3CC | #22497A | #EEF4FB | #7FD1FF |
| chalk | 칠판 | dark | #22302A | #ECEBE2 | #F4D35E | #EFEDE4 | #9AA59D | #2F3F37 | #EFEDE4 | #F4D35E |
| cocoa | 코코아 | dark | #2A211C | #F3E9DD | #E8B26A | #F5EDE3 | #A89A8C | #3A2E27 | #F5EDE3 | #E8B26A |

- `auto`(자동) = 차가 밤 모드면 `night`, 아니면 `paper`. 기본값 auto.
- 지도 위 글자·단추와 모든 하위 화면은 **고른 배색**을 따른다(앱 테마 아님).

### 1.2 파생 색 (`explorePaletteOf`, `exploreUiColorsOf`)
`mixHex(a,b,t)` = 채널마다 round(a·(1−t) + b·t).

| 이름 | 값 |
|---|---|
| inkSoft | rgba(ink, 0.34) |
| app (화면 바탕) | mixHex(paper, ink, dark ? 0.03 : 0.035) |
| surface (카드·시트) | button |
| surfaceStrong | dark rgba(255,255,255,0.10) · light rgba(ink, 0.07) |
| textPrimary / textSecondary | text / textSoft |
| textMuted | rgba(textSoft, dark ? 0.75 : 0.8) |
| textInverse (ink 위 글자) | dark paper · light #FFFFFF |
| dividerSubtle | dark rgba(255,255,255,0.08) · light rgba(ink, 0.08) |
| blue | fresh (누를 수 있는 값, 깃발, '도착' 글자에만) |
| danger | dark #FF9A93 · light #B42318 |
| 기록 중 지난 선 | mixHex(ink, paper, 0.55) (불투명) |
| 무채색 알약 | mixHex(button, ink, light 0.08 / dark 0.14) |
| 꺼진 알약 바탕 / 글자 | mixHex(button, ink, 0.32) / mixHex(그 바탕, textInverse, 0.32) |

### 1.3 새 길 색
**새 길을 다른 색으로 칠하지 않는다**(2026-10-08~). 지도·결과·카드·다시 보기 모두 ink. 이번 기록은 "지난 선을 옅게(mix 0.55) + 이번 선 ink에 paper 테두리"로 드러난다.

### 1.4 지도 위 기타
- 모눈: light rgba(0,0,0,0.063) · dark rgba(255,255,255,0.05). 굵기 0.5.
- 내 점: 채움 ink, 테 #FFFFFF(light)/paper(dark), 방향 세모 ink. 오차 원 halo @0.14(light)/0.2(dark).
- 멀리 볼 때 점: ink @0.7.
- 그림자 공통: 도구·동그라미 `0 2 16 rgba(0,0,0,0.12)`, 길잡이 `0 2 12 rgba(0,0,0,0.14)`, 시트 `0 -2 32 rgba(0,0,0,0.1)`.
- 인스타 그라데이션 #FEDA75 #FA7E1E #D62976 #962FBF #4F5BD5.

## 2. 지도 (MapLibre)

### 2.1 층 (아래 → 위)
background(#F7F7F5 고정) → paper-fill-<hex>(배색 종이, background 층) → paper-anchor(빈 line) → paper-grid-lines → real-*(실제 지도 50층, 평소 숨김) → explore-dots(멀리) → explore-mine(내 길) → explore-live(-tail)(-halo)(지금 그리는 선) → accuracy circle → 마커(깃발·사진 핀·내 점).
**Android 함정**: background 층은 붙인 뒤 색을 바꾸면 안 그려진다 → 배색이 바뀌면 새 id 층을 붙이고 옛 층을 뗀다.

### 2.2 모눈 (`paperGrid.ts`)
지도 좌표 선(확대하면 칸도 커짐). TILE_PT 512, 칸이 40pt보다 크면 완전히 보이고 20pt보다 작으면 사라짐, 사이는 진하기 log2(간격/20). 웹 메르카토르를 2^(floor(z)+5) 칸으로 나눠 경선·위선 LineString, 속성 lv = 그 선이 처음 나오는 가장 거친 단계. 칸 간격 = 512·2^(z−lv). 정수 줌에서 칸 32~64pt. line-width 0.5, color 위 1.4.

### 2.3 내 길 선 (`MapExploreLines`)
- 속성 level 1~5: 지난 수 ≥9→5, ≥5→4, ≥3→3, ≥2→2, 그 밖 1. 그리기 전 [1,2,1]/4 두 번 다듬기(양 끝 고정).
- **굵기** `EXPLORE_LEVEL_WIDTH_STOPS` (MapLibre 줌, base, step): [8, 1.9, 0.35] · [12, 1.7, 0.6] · [16, 1.4, 1.1] · [19, 2.2, 1.8]. width = s·(base + step·(level−1)), 줌 사이 선형.
- line-opacity by level: 1→0.92, 2→0.94, 3→0.96, 4→0.98, 5→1. line-cap/join round.
- 쓰임: 내 길 ink s=1(기록 중엔 mix 0.55 색) · 지금 선 ink s=1.15 + halo(paper, opacity 0.95, width = 위 굵기 + 3).
- 선 모양(꾸미기): 실선 · 형광펜(opacity×0.34, width×2.3) · 번짐(opacity×0.24, width×3.4, blur×1.76, 위에 실선) · 점선(dasharray [0,2.2], width×1.45, z<12.9 실선) · 긴 점선([1.6,2.4]) · 두 줄(width×0.6, gap-width×1.1).

### 2.4 멀리 볼 때 점
circle maxzoom 9.3(MapLibre), color ink, opacity 4→0.7, 8.4→0.7, 9.2→0, radius 4→2+0.5·ln(1+w), 10→3+0.8·ln(1+w) (w = z14 조각의 이음 수, 점 = 조각 가운데).

### 2.5 실제 지도 겹치기
OpenFreeMap(`https://tiles.openfreemap.org/planet`) positron 계열을 다시 칠한 스타일(clientApp `src/components/run/map/styles/runMapLight.json`·`runMapDark.json`, background 층은 뺌 → 땅 = 종이색). 켤 때 700ms, 끌 때 650ms opacity 전환, 20초 뒤 visibility none. 켰을 때만 출처 '© OpenMapTiles © OpenStreetMap' 9pt.
주요 색(light/dark): water #B7D6EB/#14212B, park #D9EAD0/#1D2A21, residential #EEEEE9/#1E1F21, building #E5E5DF/#232427, minor road #FFFFFF/#2A2C2F, major 테 #D6D6CF 안 #FFFFFF, motorway 테 #D2D2CA 안 #FFFFFF, boundary #BDBDB6/#3B3C40, 지명 글자 #3A3A36/#B3B6BA, 글자 halo rgba(255,255,255,0.9)/rgba(0,0,0,0.75). 글자 `coalesce(name:ko, name)`.

### 2.6 카메라
따라가기: 내 점을 보이는 칸 높이의 62% 지점(가로 가운데), 위치 사이 easeTo 선형 400~1300ms. 전체 보기 fitBounds 450ms, 여백 위 +24·아래 +24·좌우 68, minSpan 400m, maxZoom 16(구글). 기울이기 끔. 손이 닿으면 따라가기 멈춤.

## 3. 글자 (Pretendard — 굵기별 파일, Regular 400 · Medium 500 · SemiBold 600 · Bold 700 · ExtraBold 800)

| 역할 | 크기/줄높이/굵기/자간 |
|---|---|
| 화면 큰 제목 | 28 / – / 800 / −0.3 (동네 28/36/800/−0.5) |
| 묶음 제목 | 24 / 32 / 800 / −0.4 |
| 달 머리 | 20/26/700/−0.3, 합계 13/18/500 |
| 합계 카드 큰 숫자 | 42/52/800/−1, 단위 20/28/600(아래 여백 5), 이름 14/500 |
| 통계 큰 숫자 | 40/48/600/−0.8, 단위 17/22/500 |
| 결과 +km | 38/600/−0.6, 단위 18/500, 이름 14/500 |
| 판 숫자 셋 | 값 20(→14로 칸에 맞춤)/24/**600**/−0.4, 단위 12/16/500, 이름 12/16/500 |
| 숫자 칸 StatGrid | 이름 13/17/500, 값 22/28/**700**/−0.3, 단위 14/18/500(왼쪽 3) |
| 알약 글자 | 17/600/−0.2 |
| 길잡이 알약 | 14/500 |
| 꾸미기 시트 | 제목 19/600, 섹션 13/500, 견본 이름 12/500 |
| 목록 줄 | 제목 16/600, 메타 13/500, 거리 18/24/600/−0.3 + 단위 12/16/500 |
| 화면 위 제목 | 17/600, 보조 12/500 |
| 동네 배지 | 이름 14/20/600/−0.2, km 13/18/500 |
| 출처 | 9/500 |

숫자: 바뀔 때 자리마다 바꿈(AnimatedNumber 300ms out-cubic, 자리 간 28ms), 카운트업 금지(다시 보기만 예외). 숫자와 단위는 아래 끝 정렬 + 단위 marginBottom(Pretendard BASELINE_K 0.3555).
표기: 거리 100km 아래 소수 둘째(버림), 위 한 자리 · 시간 '3분' / '1시간 5분' · 날짜 '10월 7일 (수)' · 이름 '오후 3:03 산책'(Odomap: '오후 3:03 드라이브').

## 4. 부품

| 부품 | 값 |
|---|---|
| 화면 모서리 여백 | 16 |
| 도구 묶음(오른쪽 위) | 세로 알약 폭 40, 모서리 20, 바탕 button, 칸 40×40, 칸 사이 hairline(좌우 9, dividerSubtle), 아이콘 19 buttonIcon. 켜짐: 32 원 ink + paper 아이콘. 순서: 깃발 · 공유 · 실제 지도 · 전체 보기 |
| 현재 위치 / 카메라 | 44 원, button 바탕, 십자 22 / 카메라 21. 따라가는 중이면 ink 바탕 + paper 아이콘 |
| 길잡이 알약 | 92×34, 모서리 17, 화살 14 + 거리 14/500, 간격 6 |
| 아래 판(시트) | 바탕 surface, 모서리 28, 그림자 위 참고, 손잡이 36×4(모서리 4, textMuted @0.5, 둘레 10), 안쪽 좌우 20. 작게 = 24 + 윗부분(6 + 숫자 42 + 12 + 단추 56 + 12) + 아래. 크게 = 창 − 위 − 64. 판을 올려도 지도는 안 움직임 |
| 판 숫자 셋 | 칸 폭 (W−40−48)/3, 세로 hairline 높이 36(위 3, 좌우 12) |
| 검은 알약(주 단추) | 높이 56, 모서리 28, 폭 W−40, 바탕 ink, 아이콘 20 + 글자 17/600/−0.2 textInverse, 간격 7 |
| 멈춤 두 알약 | 사이 10, 왼쪽 0.56 폭 무채색 '길게 눌러 끝내기'(900ms 선형 차오름), 오른쪽 ink |
| 꾸미기 시트 | 떠 있는 카드 최대 480, 모서리 30, 머리 좌우 22, '닫기' 알약 34(좌우 14), 견본 칸 66·링 62(테 2)·원 54, 견본 선 `M13 33c4-9 9-13 14-9s9 5 14-6` 굵기 2.2, 자동 견본은 반반 |
| 하위 화면 | 바탕 app, 카드 surface 모서리 24, 좌우 20, 닫기 원 40, 박스 안에 박스 금지(칸은 hairline으로) |
| 합계 카드 | 좌우 18, 위 16, 큰 숫자 줄 52, hairline(위 12), 2×2 칸 |
| 목록 줄 | 위아래 12, 좌우 16, 썸네일 44(경로 굵기 1.6, surfaceStrong 바탕), 구분선 x=72부터 |
| 이동 줄(›) | 높이 56, 모서리 24, 좌우 18 |
| 통계 막대 | 높이 84, 사이 6, 모서리 4, 이번 textPrimary · 지난 textMuted · 0은 surfaceStrong 2pt |
| 동네 배지 | 모양 채움 surface, 모양 안 모눈(칸 max(9, size/8)), 테두리 ink@0.38 굵기 1.2, 선 굵기 clamp(size/190, 1.1, 2)·(1+0.22(L−1)), 진하기 0.72+0.07(L−1). 선반: 배지 84, 칸 96, 사이 12 |
| 내 점 | 상자 34, 점 13 + 테 2.5, 그림자 0 1 3 @0.18, 방향 세모 `M17 0.8 l4.2 6.4 h-8.4Z` |

아이콘 경로(24 단위): 십자 `M12 5a7 7 0 1 0 0 14a7 7 0 1 0 0-14Zm0 1.7a5.3 5.3 0 1 1 0 10.6a5.3 5.3 0 1 1 0-10.6Z`(evenodd) + `M11.15 2h1.7v4.2h-1.7zM11.15 17.8h1.7V22h-1.7zM2 11.15h4.2v1.7H2zM17.8 11.15H22v1.7h-4.2zM12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4Z` · 깃발 `M5 3h1.8v18H5zM6.8 4.2h11.4l-2.5 3.7 2.5 3.7H6.8z` · 전체 보기 `M3.5 3.5h6v1.8H5.3v4.2H3.5zM20.5 3.5h-6v1.8h4.2v4.2h1.8zM3.5 20.5h6v-1.8H5.3v-4.2H3.5zM20.5 20.5h-6v-1.8h4.2v-4.2h1.8z` · ▶ `M8.2 4.6c-.9-.55-2.05.1-2.05 1.15v12.5c0 1.05 1.15 1.7 2.05 1.15l10.15-6.25a1.35 1.35 0 0 0 0-2.3Z` · 길잡이 화살 `M7 0.8 12.6 12.4 7 9.6 1.4 12.4Z`(14 단위). 나머지(접힌 지도·팔레트·카메라·발자국): clientApp `src/components/explore/ExploreIcons.tsx`, `pins/PinIcons.tsx`, `TabIcons`.

## 5. 움직임
| 대상 | 값 |
|---|---|
| 누름 | 스프링 in {stiffness 320, damping 18, mass 0.56} out {220, 14, 0.56}; 도구 0.78배, 현재 위치 1.11배, 알약 0.868배 |
| 시트 이동 | 스프링 {stiffness 1000, mass 3, damping 110, overshootClamping} |
| 글 바꾸기 | 나감 120ms, 들어옴 180ms(100ms 늦게) |
| 실제 지도 | 나타남 700ms, 사라짐 650ms |
| 길잡이 | 나타남 260 · 사라짐 180 · 자리 320ms out-cubic |
| 꾸미기 시트 | 300ms out-cubic 들어옴(24pt 위로), 220ms in-cubic 나감 |
| 칸 고르기 | 스프링 {17, 230, 0.7} |
| 숫자 | 300ms out-cubic, 자리 간 28ms |
| 진동 | 고르기 selection, 끝냄 success |
| 동작 줄이기 | 모두 바로 끝 모습 |

## 6. 다시 보기 (`growthDraw`)
- 판 360×640pt를 ×3 → 1080×1920, 30fps.
- 글: 날짜 줄 y 92 Medium 15 textSoft 가운데 · 큰 숫자 y 144 SemiBold 40 text + 'km' Medium 17 textSoft(0.18칸 띄움) · 작은 줄 y 172 Medium 14 '처음 걸은 길 0.33km'. 숫자는 가장 넓은 숫자 폭을 칸으로 잡아 자리 고정, 그린 만큼 함께 오름(smoothstep).
- 지도 상자 {x 18, y 196, w 324, h 386, padding 16}. 로고 오른쪽 여백 22, y 606.
- 바탕: 종이 + 44pt 모눈(판 가운데 기준, 1px). 한 지역 다시 보기면 지역 모양(ink 채움 0.045 light/0.07 dark, 테 ink 0.3, 굵기 1).
- 선: 한 잉크, 기본 1.8pt(넓은 판은 clamp(18·pt/m, 0.6, 1.8)), 단계 굵기 비율은 지도와 같게.
- 앞머리 점: ink r 3 + 둘레 빛 r 7.5 @0.2, 걸은 차례를 따라 Trim, 다시 지날 때마다 한 단계 굵어짐, 끝나면 360ms 흐려짐.
- 시간: 기록 하나 1100ms(새 길 없으면 250), 합 3.5~12초로 맞춤, 처음 빈 종이 300ms, 기록 안에서 앞 82% 그리고(smoothstep) 나머지 머묾, 끝 꼬리 360ms.
- 카메라: 600m 안이면 한 곳, 옮기기 500+130·S(800~1400ms) van Wijk ρ1.6, 끝에 전체로 물러나 1400ms.
- 화면 틀: 위 기간 알약(40, 모서리 20) · 닫기, 9:16 판(그림자 0 2 20 @0.1), 아래 '다시 재생'(surfaceStrong) · '공유하기'(textPrimary 바탕) 높이 48.

## 7. 공유 카드 (`drawExploreCard`)
- 설계 폭 360: 정사각 360×360, 스토리 360×640 → ×3 파일.
- 자리: 정사각 padX 24 top 24 bottom 24 gap 18 · 스토리 padX 28 top 68 bottom 76 gap 28.
- 차례: 날짜(Medium 13 textSoft '2026.10.7 수') · 한마디(Bold 17, 1줄) · 길 상자 · 큰 숫자 Bold 40 '+0.18' + 'km' Bold 18 textSoft(+6) · 이름 Medium 13 textSoft '처음 걸은 길' · 한 줄 Medium 13 text '0.28km · 3분 · 412걸음' · 로고 오른쪽 아래.
- 계산값(한마디 없음): 정사각 길 상자 24,52.4 312×194.4 · 기준선 큰 숫자 293.6 / 이름 313 / 한 줄 335. 스토리 28,106.4 304×358.4 · 521.6 / 541 / 563.
- 바탕: 종이(모눈 44/0.5) · 흰색 · 투명 · 투명 흰 글자.
- 선: 굵기 2, 진하기 0.92, 자주 지난 길 더 굵게. 출발·도착 가림(200m) — 발자취는 기본 꺼짐(Odomap은 차라서 기본 켬, 300m).
- 영상: 300ms 빈 카드 → 2600ms 그림 → 앞머리 360ms 흐려짐 → 1.5초 머묾(약 4.8초).
- 미리보기 화면: 카드 그림자 아래 10, 시그마 20, 0.12 · 비율 바꿈 340ms · 보내기 단추 원 52 + 이름 12/500, 칸 94: 저장(textPrimary) · 스토리(인스타 그라데이션) · 공유(surfaceStrong).

## 8. 시안 이미지
`/Users/dmyeon/clientApp/docs/redesign-2026-10-07/`: `2_발자취탭.png`(판 전/후, 종이·밤) · `4_발자취_시트.png`(판 다섯 상태) · `3_발자취기록.png` · `7_발자취_판_깃발_꾸미기.png`(꾸미기 시트·깃발) · `8_선_배색.png`(선 6, 배색) · `10_동네_사진핀.png`(동네 묶음·동네 하나·동네 다시 보기·사진 핀). 시안은 10-07~08 것이라 1.3 등 위 '지금은 없는 것'은 따르지 않는다.
