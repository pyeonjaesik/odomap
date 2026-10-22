// 러닝·걷기 기록기(순수 계산, React·네이티브 없음).
// 저장소(runRecorderStore)가 네이티브 위치 묶음(1초마다)을 push하고 snapshot으로 화면 숫자를 읽는다. 끝낼 때와 앱을 다시 켰을 때는
// summarizeTrack/restoreRunTracker가 같은 기록기에 원본 점·일시정지를 시각순으로 다시 넣어 같은 답을 낸다(기록 중 화면 = 저장 값).
//
// 두 갈래로 계산한다.
//  - 선(segments, 지도·경로 썸네일·공유): 받은 점을 거의 그대로 잇는다(아래 거르기). 다듬지 않는다 — 그림은 기기가 준 자리 그대로.
//  - 거리·움직인 시간·1km 구간: runDistance.ts가 같은 점을 칼만 필터·스무딩·도플러 속도로 다듬어 잰다. 선의 길이를 재면 GPS 흔들림이
//    옆으로 더해져 늘어났다(docs/running-accuracy.md: 예전 방식은 흔한 잡음에서 +3~17%, 제자리 10분에 수백 m).
// 선의 거르기(docs/running-impl-notes.md 4절):
//  - 제자리 흔들림의 '직전 받은 점'은 직전 받은 점 + 그 둘레에서 붙잡아 둔 점들의 평균 위치로 잰다.
//  - 첫 점 기준(20m 이하)은 기록 시작뿐 아니라 다시 시작한 뒤(새 구간)의 첫 점에도 쓴다.
//  - 튄 점이 RUN_FILTER.outlierRecoveryCount개 이어지고 그 점들끼리는 말이 되는 속도면 기준점 쪽이 틀렸다고 보고 그 점들로 새 구간을
//    시작한다(끊긴 사이는 선으로 잇지 않음). 구간에 기준점 하나뿐이었으면(시작 첫 점이 틀린 경우) 그 점은 버린다.
//  거리 추정은 선의 기준점과 따로 튄 점을 가린다(선의 기준점이 튄 점이어도 거리는 빠지지 않는다). 선이 새 구간으로 나뉘어도 거리는
//  사람이 낼 수 있는 속도로 이어졌으면 잇고, 사용자 일시정지 사이만 잇지 않는다.
// 시각(t)은 앱의 Date.now()와 같은 시계여야 한다(일시정지·전체 시간·GPS 상태를 이 값으로 잰다).

import { haversineM, METERS_PER_DEGREE, metersPerDegreeLng } from './geo';
import { DEFAULT_SIMPLIFY_TOLERANCE_M } from './polyline';
import { DopplerCheck, Odometer, PathSmoother, StepLog, type OdometerCrossing } from './runDistance';
import { ElevationPiece } from './runElevation';

export type Sport = 'run' | 'walk';

export type RawPoint = {
  lat: number;
  lng: number;
  /** ms epoch */
  t: number;
  /** 수평 오차 반경(m) */
  acc: number | null;
  /** 고도(m) */
  alt: number | null;
  /** 고도 오차(m). 없으면 기본값(runElevation.ts) */
  vacc?: number | null;
  /** 기기가 잰 속도(m/s, GNSS 도플러). 거리 추정의 속도 측정으로 쓴다(runDistance.ts) */
  speed: number | null;
  /** 진행 방향(도, 북쪽에서 시계 방향) */
  course: number | null;
  /** 속도 오차(m/s). 없으면 기본값 */
  sacc?: number | null;
  /** 그 시각의 심박(bpm, 워치 — 거리 계산에는 쓰지 않는다. 원본 점 10번째 칸) */
  hr?: number | null;
};

/** 사용자가 누른 일시정지. end가 null이면 아직 멈춰 있다. */
export type RunPause = { start: number; end: number | null };
/** 네이티브 기록 파일의 일시정지 줄 `{"type":"pause"|"resume","t":ms}`. */
export type RunPauseEvent = { type: 'pause' | 'resume'; t: number };

/** 서버 WorkoutSplit과 같은 모양. */
export type RunSplit = {
  /** 1부터 */
  km: number;
  /** 마지막 조각 말고는 1000 */
  distanceM: number;
  /** 이 조각의 움직인 시간(초, 소수 1자리) */
  timeS: number;
  elevationDeltaM: number | null;
};

export type GpsQuality = 'none' | 'weak' | 'good';
export type GpsFix = { t: number; acc: number | null };

export type RunSnapshot = {
  /** m, 소수 1자리 */
  distanceM: number;
  /** 정수 초 */
  movingTimeS: number;
  /** 정수 초. 시작부터의 벽시계 − 일시정지 */
  elapsedTimeS: number;
  /** 움직인 시간 ÷ km. 50m 미만이면 null */
  paceSecPerKm: number | null;
  /**
   * 받은 점(선). 일시정지·GPS 끊김마다 나뉜다. 바뀐 구간만 새 배열이고, 지난 snapshot 뒤로 아무것도 안 바뀌었으면 같은 배열을
   * 돌려준다(메모용). 고치지 말 것.
   */
  segments: ReadonlyArray<ReadonlyArray<RawPoint>>;
  lastAccepted: RawPoint | null;
  /** 끝난 1km 구간 + 지금까지의 마지막 조각 */
  splits: RunSplit[];
  elevationGainM: number | null;
  gpsQuality: GpsQuality;
  /** GPS가 약했던 구간(오차 반경이 크거나, 위치가 끊겼거나, 크게 벗어난 점이 있던 곳). 지도에 흐리게 그릴 때 쓴다 */
  lowAccuracySpans: RunLowAccuracySpan[];
};

/** snapshot의 숫자만(음성 안내·잠금 화면이 쓰는 것) */
export type RunLiveNumbers = Pick<RunSnapshot, 'distanceM' | 'movingTimeS' | 'elapsedTimeS' | 'paceSecPerKm'>;

export type RunLowAccuracySpan = {
  /** segments의 몇 번째 선인지 */
  segment: number;
  /** 시각(ms). 그 선의 점 중 t가 이 사이인 점들이 약했던 곳이다 */
  startT: number;
  endT: number;
  /** 그 시각까지의 누적 거리(m, 소수 1자리) */
  startDistanceM: number;
  endDistanceM: number;
};


/** docs/running-impl-notes.md 4절·docs/running-accuracy.md의 값. 바꾸면 문서도 같이 바꾼다. */
export const RUN_FILTER = {
  /** 정확도: 오차 반경이 이보다 크면 버림(값이 없으면 받음) */
  maxAccuracyM: 25,
  /** 구간 첫 점(시작·다시 시작 직후)은 이 이하일 때만. GPS 상태 'good'의 기준이기도 하다 */
  firstPointMaxAccuracyM: 20,
  /** 튄 점: 직전 받은 점에서의 속도가 이보다 빠르면 버림 */
  maxSpeedRunMps: 10,
  maxSpeedWalkMps: 5,
  /** 튄 점이 이만큼 이어지고 서로는 말이 되면 그 점들로 새 구간을 시작(2 이상) */
  outlierRecoveryCount: 5,
  /** 선(그림)의 제자리 흔들림 반경 = max(jitterMinM, min(오차, jitterAccuracyCapM) × jitterAccuracyFactor). 이보다 덜 움직이면 선에 안 넣음 */
  jitterMinM: 3,
  jitterAccuracyCapM: 20,
  jitterAccuracyFactor: 0.6,
  /** 움직인 시간: 다듬은 위치가 이 속도 이상으로 움직였고 */
  movingMinSpeedMps: 0.5,
  /** 그 사이 위치가 끊긴 시간이 이 이하인 것만 더함(초). 끊긴 사이는 이은 속도가 위 기준 이상일 때만 */
  movingMaxGapS: 300,
  splitDistanceM: 1000,
  /** 고도 상승(runElevation.ts): 쓸 수 있는 점 중 고도 값이 있는 점이 이 비율보다 많을 때만 */
  elevationMinCoverage: 0.5,
  /** 튄 고도를 지우는 가운데 맞춘 중앙값의 점 수(홀수) */
  elevationMedianPoints: 9,
  /** 그다음 가운데 맞춘 이동 평균의 점 수(홀수, 1초 간격이면 ±20초) */
  elevationSmoothingPoints: 41,
  /** 이력: 이만큼, 그리고 elevationVerticalFactor × 고도 표준편차(vacc × accuracyToSigma)만큼 오르내려야 방향이 바뀐 것으로 본다 */
  elevationHysteresisM: 4,
  elevationVerticalFactor: 2,
  /** 고도 오차(vacc)가 이보다 큰 점은 고도를 쓰지 않는다(m) */
  maxVerticalAccuracyM: 30,
  /** 고도 오차를 모르는 점(가져온 기록·서버 점·예전 세션 파일)은 이 값으로 본다(m) — 기압계를 섞은 고도쯤으로 본다 */
  defaultVerticalAccuracyM: 4,
  /** 페이스: 이 거리 미만이면 표시 안 함 */
  paceMinDistanceM: 50,
  /** 경로 줄이기(저장·공유용 Douglas–Peucker). 기록 중 화면엔 받은 점 그대로 */
  simplifyToleranceM: DEFAULT_SIMPLIFY_TOLERANCE_M,
  /** GPS 상태: 최근 이 시간(초) 안에 받은 위치로 판단 */
  // 5초였을 때 갤럭시(Z Flip4, 실내에 서 있음)에서 위치가 7초 남짓마다 와서 'GPS 잡았어요' ↔ '잡는 중'이 계속 바뀌었다(2026-10-01).
  // Android 위치 서비스는 멈춰 있으면 1초 요청에도 드물게 준다. 달리는 동안은 1초마다 오므로 12초 끊기면 그때 '잡는 중'.
  gpsRecentS: 12,

  // ── 거리 추정(runDistance.ts). 정확도 평가(docs/running-accuracy.md)로 맞춘 값
  /** 칼만 필터의 가속도 잡음 밀도(m²/s³) = processNoise + processNoiseSpeedK × 속도². 클수록 위치를 빨리 따라가고(모퉁이에
   *  강함) 흔들림도 더 따라간다. 모퉁이에서 필요한 가속도(v²/r)가 속도의 제곱에 비례해서 속도로 정한다 */
  processNoise: 0.1,
  processNoiseSpeedK: 0.15,
  /** 오차 반경(68%) → 축마다 표준편차 */
  accuracyToSigma: 0.67,
  minPositionSigmaM: 1.5,
  maxPositionSigmaM: 25,
  /** 오차 반경이 없는 점은 이 값으로 본다 */
  defaultAccuracyM: 10,
  /** 잔차가 축마다 이 배 표준편차를 넘으면 그만큼 무게를 줄인다 */
  robustGate: 3,
  /** 예측에서 축마다 이 배 표준편차 그리고 hardMinM 넘게 벗어난 점은 버린다(튄 점) */
  hardGate: 5,
  hardMinM: 20,
  /** 도플러 속도를 속도 측정으로 쓰는지(1/0) */
  dopplerAiding: 1,
  /** 방향(course)을 믿는 최소 속도(m/s) */
  dopplerMinSpeedMps: 0.5,
  /** 기기가 속도 오차를 주지 않을 때의 값(m/s) */
  dopplerDefaultSigmaMps: 0.5,
  /** 팔·몸 흔들림(m/s) */
  dopplerBodySigmaMps: 0.3,
  /** 방향 오차(도) */
  dopplerCourseSigmaDeg: 5,
  /** 도플러 확인: 이 시간(초) 동안 위치 이동과 도플러 이동을 비교하고 */
  dopplerCheckWindowS: 30,
  /** 위치가 이만큼(또는 오차 반경 2.5배) 넘게 움직였을 때만 판단하며 */
  dopplerCheckMinMoveM: 25,
  /** 세 배 넘게 어긋나는 일이 이만큼(초) 이어지면 이 기록에서 도플러를 끈다 */
  dopplerMismatchLimit: 10,
  /** 정지: 최근 stillCount개 도플러 속도의 평균이 이보다 느리고 */
  stillSpeedMps: 0.5,
  /** 그중 가장 빠른 것도 이보다 느리면 멈춰 있다고 보고 위치를 얼린다(속도 0을 측정으로 넣음) */
  stillMaxSpeedMps: 0.9,
  stillCount: 3,
  stillSigmaMps: 0.1,
  /** 얼린 위치에서 이만큼(또는 3 표준편차) 넘게 벗어난 점이 오면 얼리지 않는다(m) */
  stillBreakM: 30,
  /** 필터를 새로 시작할 때 속도를 모르면 */
  initialSpeedSigmaMps: 10,
  /** 고정 지연 스무딩: 이만큼 뒤의 점까지 보고 확정한다(점 수). 그 전의 점은 snapshot이 잠정으로 더한다 */
  smoothingLag: 10,
  /** 기록 중 화면: 최근 liveTailDopplerS초 동안 도플러로 이어진 점이 없으면(도플러를 주지 않는 기기·가짜 위치) 확정 안 된 끝의 점을
   *  이만큼까지 늦게 넣는다(멈췄을 때·요약은 모두) */
  liveTailHoldback: 10,
  liveTailDopplerS: 60,
  /** 다듬은 위치가 이만큼 움직였을 때만 거리에 더한다(m) */
  stepMinM: 2,
  /** 도플러로 이어진 곳의 거리를 다듬은 속력의 적분으로 잰다(1/0). 앞뒤 위치 사이가 이보다 길게 끊겼으면 곧은 선으로(초) */
  speedIntegral: 1,
  speedIntegralMaxGapS: 3,
  /** 정지(도플러가 없을 때): 최근 이 시간(초) 동안 */
  stillWindowS: 20,
  /** 이 속도(m/s)만큼도 움직이지 않았으면 멈춰 있는 것으로 보고 거리에 더하지 않는다 */
  stillWindowSpeedMps: 0.3,
  /** 위치가 이보다 길게 끊기면 필터를 새로 시작한다(초) */
  gapResetS: 10,
  /** 약한 구간: 오차 반경이 이보다 크거나 */
  weakAccuracyM: 15,
  /** 위치가 이보다 오래 끊긴 곳(초) */
  weakGapS: 5,
  /** 약한 점 사이가 이만큼(초) 안이면 한 구간으로 잇고 */
  weakMergeS: 5,
  /** 이보다 짧은 구간은 버린다(초) */
  weakMinSpanS: 5,
  /**
   * 걸음 정지(2026-10-08 — 발자취처럼 걸음을 세는 기록, runDistance StepLog): 최근 stepStillWindowS초 동안 걸음이 stepStillMinSteps보다 적고
   * 다듬은 속력이 stepStillMaxSpeedMps보다 느리면 서 있는 것 — 거리·움직인 시간에 더하지 않는다. 실내에서 흔들리는 위치가 거리로 붙었다
   * (사용자: "워치를 차고 있지도 않는데, 왜 워치는 지 스스로 움직이는거지? 지가 그냥 앞으로 가는데", "핸드폰을 건들지 않았어 … 들고 움직이지도
   * 않았는데" 새로 걸은 길 0.03km). 빠르기는 창 동안 실제로 옮겨 간 거리로 잰다(흔들리는 위치는 순간 속력이 튀어도 제자리를 맴돈다) — 빠른 걷기보다
   * 빠르게 옮겨 가면(탈것·자전거) 그대로 센다. 걸음을 세지 않는 기록(땅따먹기·권한 없음·예전 파일)은 그대로다.
   */
  stepStillWindowS: 20,
  stepStillMinSteps: 4,
  stepStillMaxSpeedMps: 2.5,
} as const;

export type RunFilter = { -readonly [K in keyof typeof RUN_FILTER]: number };

export type RunTrackerOptions = {
  sport: Sport;
  /** 기록 시작(ms epoch). 이보다 이른 점은 버린다 */
  startedAt: number;
  /** 걸음 누계 [시각 ms, 누계](세션 파일의 steps 줄 — 다시 켤 때·요약). 기록 중에 오는 것은 addSteps로 */
  steps?: ReadonlyArray<readonly [number, number]>;
  /** 기준값 바꿔 보기(가짜 경로 재생으로 맞출 때) */
  filter?: Partial<RunFilter>;
};

export type RunTracker = {
  readonly sport: Sport;
  readonly startedAt: number;
  /**
   * 점을 시각순으로 넣는다. 이번에 선에 들어간 점을 돌려준다 — GPS 끊김 뒤 새 구간을 시작할 때는 앞서 넣었던 점이 섞일 수 있고,
   * 틀린 첫 점이 빠지기도 하니 그릴 땐 snapshot().segments를 기준으로 한다.
   * 일시정지 시각 이후의 점은 버리고, 일시정지 전에 찍혔는데 늦게 온 점은 받는다. 다시 시작한 뒤에 온 이전 구간의 점은 버린다.
   */
  push(points: ReadonlyArray<RawPoint>): RawPoint[];
  /** 사용자 일시정지. 네이티브 파일에 적히는 것과 같은 시각을 주면 summarizeTrack과 답이 같다 */
  pause(atMs: number): void;
  /** 다시 시작. 새 구간이 시작되고 멈춘 사이는 잇지 않는다 */
  resume(atMs: number): void;
  /** 걸음 누계 하나(세션 파일의 steps 줄과 같은 값 — 시각순). 걸음이 없으면 서 있는 것으로 보고 거리에 더하지 않는다(RUN_FILTER stepStill*) */
  addSteps(atMs: number, count: number): void;
  isPaused(): boolean;
  snapshot(nowMs: number): RunSnapshot;
  /**
   * snapshot의 거리·움직인 시간·전체 시간·평균 페이스만(같은 계산이라 값이 같다). 선·1km 구간·고도·약한 구간·GPS 상태는 세지 않는다 —
   * 앱이 뒤에 있어 그릴 화면이 없는 동안 음성 안내·잠금 화면에 쓸 숫자만 낼 때(runRecorderStore).
   */
  liveNumbers(nowMs: number): RunLiveNumbers;
  /** liveNumbers + 1km 구간(snapshot과 같은 계산 — 앱이 뒤에 있어도 워치에 지난 1km를 맞게 보낼 때). 선·고도 그림·GPS 상태는 세지 않는다 */
  liveSummary(nowMs: number): RunLiveNumbers & Pick<RunSnapshot, 'splits'>;
};

type SegmentState = {
  points: RawPoint[];
  /** 이 구간이 받기 시작하는 시각(시작·다시 시작·GPS 끊김 뒤 새 기준점) */
  startAt: number;
  /** snapshot에 내준 복사본(점 수가 같으면 그대로 다시 쓴다) */
  view: RawPoint[] | null;
};

const MAX_RECENT_FIXES = 16;

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

function accuracyOf(point: { acc: number | null }): number | null {
  return typeof point.acc === 'number' && Number.isFinite(point.acc) && point.acc >= 0 ? point.acc : null;
}

function altitudeOf(point: RawPoint): number | null {
  return typeof point.alt === 'number' && Number.isFinite(point.alt) ? point.alt : null;
}

function verticalAccuracyOf(point: RawPoint): number | null {
  return typeof point.vacc === 'number' && Number.isFinite(point.vacc) && point.vacc >= 0 ? point.vacc : null;
}

function isUsable(point: RawPoint): boolean {
  const { lat, lng, t } = point;
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || !Number.isFinite(t)) return false;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return false;
  // 위치를 못 잡은 기기가 (0,0)을 내보내는 경우
  return !(lat === 0 && lng === 0);
}

function jitterRadiusM(acc: number | null, f: RunFilter): number {
  return Math.max(f.jitterMinM, Math.min(acc ?? 0, f.jitterAccuracyCapM) * f.jitterAccuracyFactor);
}

/** 움직인 시간 ÷ km. 거리가 기준(50m) 미만이거나 시간이 없으면 null. 상세 화면도 서버 값으로 같은 함수를 쓰면 숫자가 같다. */
export function averagePaceSecPerKm(
  distanceM: number,
  movingTimeS: number,
  minDistanceM: number = RUN_FILTER.paceMinDistanceM,
): number | null {
  if (!(distanceM >= minDistanceM) || !(distanceM > 0) || !(movingTimeS > 0)) return null;
  return movingTimeS / (distanceM / 1000);
}

/**
 * GPS 상태: 최근 gpsRecentS초 안에 받은 위치가 없으면 'none'(잡는 중), 그중 하나라도 구간을 시작할 만큼 정확하면(20m 이하,
 * 오차 값이 없으면 받음) 'good', 아니면 'weak'. 기록 전 화면에서도 아무 위치 출처로 부를 수 있다.
 */
export function assessGpsQuality(
  fixes: ReadonlyArray<GpsFix>,
  nowMs: number,
  filter: Pick<RunFilter, 'gpsRecentS' | 'firstPointMaxAccuracyM'> = RUN_FILTER,
): GpsQuality {
  let seen = false;
  for (const fix of fixes) {
    if (!Number.isFinite(fix.t) || nowMs - fix.t > filter.gpsRecentS * 1000) continue;
    seen = true;
    const acc = accuracyOf(fix);
    if (acc === null || acc <= filter.firstPointMaxAccuracyM) return 'good';
  }
  return seen ? 'weak' : 'none';
}

/** 네이티브 파일의 pause/resume 줄을 일시정지 구간으로. 짝이 안 맞는 줄(멈춤 중 또 멈춤 등)은 건너뛴다. */
export function pausesFromEvents(events: ReadonlyArray<RunPauseEvent>): RunPause[] {
  const sorted = events.filter(event => Number.isFinite(event.t)).sort((a, b) => a.t - b.t);
  const pauses: RunPause[] = [];
  for (const event of sorted) {
    const open = pauses.length > 0 && pauses[pauses.length - 1].end === null;
    if (event.type === 'pause' && !open) pauses.push({ start: event.t, end: null });
    else if (event.type === 'resume' && open) pauses[pauses.length - 1].end = event.t;
  }
  return pauses;
}

function mergeFilter(filter: Partial<RunFilter> | undefined): RunFilter {
  const merged: RunFilter = { ...RUN_FILTER };
  if (filter) {
    for (const key of Object.keys(merged) as (keyof RunFilter)[]) {
      const value = filter[key];
      if (typeof value === 'number' && !Number.isNaN(value)) merged[key] = value;
    }
  }
  return merged;
}

class Tracker implements RunTracker {
  readonly sport: Sport;
  readonly startedAt: number;
  private readonly f: RunFilter;
  private readonly maxSpeedMps: number;
  private readonly recoveryCount: number;
  private readonly splitM: number;

  private readonly pauses: RunPause[] = [];
  private readonly segments: SegmentState[] = [];
  private current: SegmentState;
  /** 처리한 마지막 점 시각. 같거나 이른 점(중복·순서 뒤바뀜)은 버린다 */
  private lastT = -Infinity;

  // 선의 기준점(직전 받은 점)과 그 둘레에 붙잡아 둔 점들. 위치는 기준점에서의 동·북 m
  private anchor: RawPoint | null = null;
  private anchorKx = 0;
  private clusterX = 0;
  private clusterY = 0;
  private clusterN = 0;
  private outliers: RawPoint[] = [];
  private recovering = false;

  /** 거리 추정: 일시정지로 나뉜 조각마다 필터 하나, 기록 전체에 누계 하나(runDistance.ts) */
  private readonly doppler: DopplerCheck;
  private readonly odometer: Odometer;
  /** 걸음 누계(걸음을 세는 기록만 — 서 있는지 판단) */
  private readonly steps = new StepLog();
  private piece: PathSmoother;
  /** 오르막: 일시정지로 나뉜 조각마다 하나(runElevation.ts). 쓸 수 있는 점 수와 그중 고도가 있는 점 수(고도를 믿을지) */
  private readonly elevation: ElevationPiece[] = [];
  private validCount = 0;
  private altitudeCount = 0;
  private readonly recentFixes: GpsFix[] = [];

  private version = 0;
  private viewVersion = -1;
  private view: ReadonlyArray<ReadonlyArray<RawPoint>> = [];

  constructor(options: RunTrackerOptions) {
    this.sport = options.sport;
    this.startedAt = options.startedAt;
    this.f = mergeFilter(options.filter);
    this.maxSpeedMps = this.sport === 'walk' ? this.f.maxSpeedWalkMps : this.f.maxSpeedRunMps;
    this.recoveryCount = Math.max(2, Math.floor(this.f.outlierRecoveryCount));
    this.splitM = this.f.splitDistanceM > 0 ? this.f.splitDistanceM : RUN_FILTER.splitDistanceM;
    this.doppler = new DopplerCheck(this.f);
    for (const [at, count] of options.steps ?? []) this.steps.add(at, count);
    this.odometer = new Odometer(this.f, this.splitM, this.maxSpeedMps, this.steps);
    this.piece = this.newPiece();
    this.elevation.push(new ElevationPiece(this.f));
    this.current = this.newSegment(this.startedAt);
    this.segments.push(this.current);
  }

  push(points: ReadonlyArray<RawPoint>): RawPoint[] {
    const accepted: RawPoint[] = [];
    for (const point of points) this.take(point, accepted);
    return accepted;
  }

  pause(atMs: number): void {
    if (this.isPaused() || !Number.isFinite(atMs)) return;
    const previous = this.pauses[this.pauses.length - 1];
    const floor = Math.max(this.startedAt, previous?.end ?? -Infinity);
    this.pauses.push({ start: Math.max(atMs, floor), end: null });
  }

  resume(atMs: number): void {
    const last = this.pauses[this.pauses.length - 1];
    if (last === undefined || last.end !== null || !Number.isFinite(atMs)) return;
    last.end = Math.max(atMs, last.start);
    // 멈춘 사이는 잇지 않는다: 지난 조각의 남은 위치를 확정하고 새 조각을 시작한다
    this.piece.close();
    this.odometer.startPiece();
    this.piece = this.newPiece();
    this.elevation[this.elevation.length - 1].close();
    this.elevation.push(new ElevationPiece(this.f));
    this.startSegment(last.end);
  }

  addSteps(atMs: number, count: number): void {
    this.steps.add(atMs, count);
  }

  isPaused(): boolean {
    const last = this.pauses[this.pauses.length - 1];
    return last !== undefined && last.end === null;
  }

  snapshot(nowMs: number): RunSnapshot {
    const odometer = this.liveOdometer();
    const segments = this.segmentsView();
    return {
      ...this.numbersOf(odometer, nowMs),
      segments,
      lastAccepted: this.lastAccepted(),
      splits: this.splits(odometer),
      elevationGainM: this.elevationGain(),
      gpsQuality: assessGpsQuality(this.recentFixes, nowMs, this.f),
      lowAccuracySpans: this.lowAccuracySpans(odometer),
    };
  }

  liveNumbers(nowMs: number): RunLiveNumbers {
    return this.numbersOf(this.liveOdometer(), nowMs);
  }

  liveSummary(nowMs: number): RunLiveNumbers & Pick<RunSnapshot, 'splits'> {
    const odometer = this.liveOdometer();
    return { ...this.numbersOf(odometer, nowMs), splits: this.splits(odometer) };
  }

  /**
   * 아직 확정하지 않은 마지막 몇 점(지금 조각)을 누계의 복사본에 더해 본다. 상태는 바꾸지 않는다.
   * 도플러를 주지 않는 기기(최근 1분 동안 도플러로 이어진 점이 없음)에서는 기록 중에 확정 안 된 끝의 점(최대 liveTailHoldback개)을
   * 아직 넣지 않는다: 위치만으로는 뒤 점이 와야
   * 자리가 잡혀서, 넣으면 화면 거리가 늘었다 줄었다 했다(σ8m 합성 평가에서 최대 17m). 그만큼(10초 안팎) 늦게 오르는 대신 줄지 않는다.
   * 멈춘 뒤(끝내기는 늘 먼저 멈춘다)·요약(summarizeTrack)은 모두 넣으므로 끝낼 때 보인 숫자 = 저장되는 숫자다.
   */
  private liveOdometer(): Odometer {
    const odometer = this.odometer.clone();
    const tail = this.piece.tail();
    if (!this.isPaused() && !this.piece.aidedWithin(this.f.liveTailDopplerS * 1000)) {
      let held = 0;
      while (held < this.f.liveTailHoldback && tail.length > 0 && !tail[tail.length - 1].aided) {
        tail.pop();
        held += 1;
      }
    }
    for (const sample of tail) odometer.feed(sample);
    odometer.addOpenStep();
    return odometer;
  }

  private numbersOf(odometer: Odometer, nowMs: number): RunLiveNumbers {
    const elapsedMs = this.elapsedMs(nowMs);
    const distanceM = round1(odometer.distance);
    // 기기 시각과 앱 시각이 조금 어긋나도 움직인 시간이 전체 시간을 넘지 않게
    const movingTimeS = Math.floor(Math.min(odometer.movingMs, elapsedMs) / 1000 + 1e-6);
    return {
      distanceM,
      movingTimeS,
      elapsedTimeS: Math.floor(elapsedMs / 1000 + 1e-6),
      paceSecPerKm: averagePaceSecPerKm(distanceM, movingTimeS, this.f.paceMinDistanceM),
    };
  }

  // ── 점 하나

  private take(point: RawPoint, accepted: RawPoint[]): void {
    if (!isUsable(point) || point.t <= this.lastT) return;
    this.lastT = point.t;
    this.recentFixes.push({ t: point.t, acc: point.acc });
    if (this.recentFixes.length > MAX_RECENT_FIXES) this.recentFixes.shift();
    // 시작 전, 다시 시작하기 전(이전 구간에 늦게 온 점), 일시정지 동안
    if (point.t < this.current.startAt || (this.isPaused() && point.t >= this.pauses[this.pauses.length - 1].start)) return;
    const acc = accuracyOf(point);
    if (acc !== null && acc > this.f.maxAccuracyM) return;
    // 거리는 선과 따로 다듬은 위치로 잰다(튄 점·첫 점·끊김은 거리 추정이 스스로 가린다)
    this.piece.add(point);
    this.addAltitude(point);
    this.consider(point, accepted);
  }

  /** 선(그림): 시각·일시정지·정확도를 통과한 점을 첫 점 → 튄 점 → 제자리 흔들림 순으로 본다. */
  private consider(point: RawPoint, accepted: RawPoint[]): void {
    const anchor = this.anchor;
    const acc = accuracyOf(point);
    if (anchor === null) {
      if (acc !== null && acc > this.f.firstPointMaxAccuracyM) return;
      this.accept(point, accepted);
      return;
    }
    const d = haversineM(anchor, point);
    // 1초보다 촘촘한 점에서 시각 흔들림이 속도를 부풀리지 않게 간격은 1초 이상으로 본다
    if (d / Math.max((point.t - anchor.t) / 1000, 1) > this.maxSpeedMps) {
      this.onOutlier(point, accepted);
      return;
    }
    if (this.outliers.length > 0) this.outliers = [];

    const x = (point.lng - anchor.lng) * this.anchorKx;
    const y = (point.lat - anchor.lat) * METERS_PER_DEGREE;
    const fromCluster = Math.hypot(x - this.clusterX / this.clusterN, y - this.clusterY / this.clusterN);
    // 반경만큼 움직였으면(부동소수 오차 1µm 안) 움직인 것으로 본다 — 3m/s·오차 5m(반경 3m)처럼 흔한 값이 경계에 걸린다
    if (fromCluster < jitterRadiusM(acc, this.f) - 1e-6) {
      this.clusterX += x;
      this.clusterY += y;
      this.clusterN += 1;
      return;
    }
    this.addToLine(point, accepted);
  }

  private accept(point: RawPoint, accepted: RawPoint[]): void {
    this.addToLine(point, accepted);
  }

  private addToLine(point: RawPoint, accepted: RawPoint[]): void {
    const segment = this.current;
    segment.points.push(point);

    this.anchor = point;
    this.anchorKx = metersPerDegreeLng(point.lat);
    this.clusterX = 0;
    this.clusterY = 0;
    this.clusterN = 1;
    this.version += 1;
    accepted.push(point);
  }

  private onOutlier(point: RawPoint, accepted: RawPoint[]): void {
    if (this.recovering) return;
    const last = this.outliers[this.outliers.length - 1];
    const consistent =
      last !== undefined && haversineM(last, point) / Math.max((point.t - last.t) / 1000, 1) <= this.maxSpeedMps;
    if (consistent) this.outliers.push(point);
    else this.outliers = [point];
    if (this.outliers.length >= this.recoveryCount) this.reanchor(accepted);
  }

  /** 튄 점들이 서로는 말이 된다 → 기준점 쪽이 틀렸다. 그 점들로 새 구간을 시작하고 끊긴 사이는 잇지 않는다. */
  private reanchor(accepted: RawPoint[]): void {
    const run = this.outliers;
    this.outliers = [];
    const segment = this.current;
    if (segment.points.length === 1) {
      // 구간 첫 점 하나뿐: 그 점(과 그 둘레에 붙잡아 둔 점)이 틀렸다. 선에서 버리고 같은 구간을 다시 쓴다.
      segment.points.pop();
      segment.view = null;
      this.version += 1;
    }
    this.startSegment(run[0].t);
    this.recovering = true;
    for (const point of run) this.consider(point, accepted);
    this.recovering = false;
  }

  private newPiece(): PathSmoother {
    return new PathSmoother(this.f, this.maxSpeedMps, this.doppler, sample => this.odometer.feed(sample));
  }

  private newSegment(at: number): SegmentState {
    return { points: [], startAt: at, view: null };
  }

  private startSegment(at: number): void {
    const segment = this.current;
    if (segment.points.length === 0) {
      segment.startAt = at;
    } else {
      this.current = this.newSegment(at);
      this.segments.push(this.current);
    }
    this.anchor = null;
    this.outliers = [];
    this.clusterX = 0;
    this.clusterY = 0;
    this.clusterN = 0;
  }

  // ── 누계

  /** 쓸 수 있는 점(시각·일시정지·정확도 통과)의 고도를 지금 조각에 넣는다. 고도 오차가 너무 큰 점은 고도만 뺀다. */
  private addAltitude(point: RawPoint): void {
    this.validCount += 1;
    const alt = altitudeOf(point);
    const vacc = verticalAccuracyOf(point);
    if (alt === null || (vacc !== null && vacc > this.f.maxVerticalAccuracyM)) return;
    this.altitudeCount += 1;
    this.elevation[this.elevation.length - 1].add(point.t, alt, vacc);
  }

  private hasElevation(): boolean {
    return this.validCount > 0 && this.altitudeCount / this.validCount > this.f.elevationMinCoverage;
  }

  private elevationGain(): number | null {
    if (!this.hasElevation()) return null;
    let total = 0;
    for (const piece of this.elevation) total += piece.gain();
    return round1(total);
  }

  /**
   * 시각 t의 다듬은 고도: 그 시각을 담은 조각(없으면 가장 가까운 앞 조각)에서 시각으로 보간. 조각의 고도 배열을 잇거나 복사하지 않는다
   * (기록 중 점마다 1km 지점마다 부른다 — runElevation 머리 주석).
   */
  private altitudeAtTime(t: number): number | null {
    let found: ElevationPiece | null = null;
    for (const piece of this.elevation) {
      if (piece.count === 0) continue;
      if (found !== null && piece.firstT > t) break;
      found = piece;
    }
    return found ? found.altitudeAt(t) : null;
  }

  /** 시각 t에 그리던 선(점이 있는 선 중 t 이전에 시작한 마지막 것). */
  private segmentAt(t: number): SegmentState | null {
    let found: SegmentState | null = null;
    for (const segment of this.segments) {
      if (segment.points.length === 0) continue;
      if (found !== null && segment.points[0].t > t) break;
      found = segment;
    }
    return found;
  }

  private altitudeAtCrossing(crossing: OdometerCrossing): number | null {
    return this.altitudeAtTime(crossing.t);
  }

  private altitudeAtEdge(edge: 'start' | 'end'): number | null {
    if (edge === 'start') {
      for (const piece of this.elevation) if (piece.count > 0) return piece.firstAltitude();
      return null;
    }
    for (let i = this.elevation.length - 1; i >= 0; i -= 1) if (this.elevation[i].count > 0) return this.elevation[i].lastAltitude();
    return null;
  }

  private splits(odometer: Odometer): RunSplit[] {
    const withElevation = this.hasElevation();
    const delta = (from: number | null, to: number | null) => (from !== null && to !== null ? round1(to - from) : null);
    const splits: RunSplit[] = [];
    let previousMs = 0;
    let previousAlt = withElevation ? this.altitudeAtEdge('start') : null;
    odometer.crossings.forEach((crossing, i) => {
      const alt = withElevation ? this.altitudeAtCrossing(crossing) : null;
      splits.push({
        km: i + 1,
        distanceM: this.splitM,
        timeS: round1((crossing.movingMs - previousMs) / 1000),
        elevationDeltaM: delta(previousAlt, alt),
      });
      previousMs = crossing.movingMs;
      previousAlt = alt;
    });
    const rest = odometer.distance - odometer.crossings.length * this.splitM;
    // 반올림하면 0.0m인 조각은 두지 않는다(정확히 1km에서 끝났을 때의 계산 잔여)
    if (rest >= 0.05) {
      splits.push({
        km: odometer.crossings.length + 1,
        distanceM: round1(rest),
        timeS: round1((odometer.movingMs - previousMs) / 1000),
        elevationDeltaM: delta(previousAlt, withElevation ? this.altitudeAtEdge('end') : null),
      });
    }
    return splits;
  }

  private lowAccuracySpans(odometer: Odometer): RunLowAccuracySpan[] {
    const spans = odometer.allSpans();
    if (spans.length === 0) return [];
    // segments(그림)는 점이 없는 선을 건너뛴다 — 같은 번호를 쓴다
    const drawn = this.segments.filter(segment => segment.points.length > 0);
    const out: RunLowAccuracySpan[] = [];
    for (const span of spans) {
      const segment = this.segmentAt(span.startT);
      const index = segment === null ? -1 : drawn.indexOf(segment);
      if (index < 0) continue;
      out.push({ segment: index, startT: span.startT, endT: span.endT, startDistanceM: round1(span.startD), endDistanceM: round1(span.endD) });
    }
    return out;
  }

  private elapsedMs(nowMs: number): number {
    const total = nowMs - this.startedAt;
    if (!(total > 0)) return 0;
    let paused = 0;
    for (const pause of this.pauses) {
      const end = Math.min(pause.end ?? nowMs, nowMs);
      if (end > pause.start) paused += end - pause.start;
    }
    return Math.max(0, total - paused);
  }

  private lastAccepted(): RawPoint | null {
    for (let i = this.segments.length - 1; i >= 0; i -= 1) {
      const points = this.segments[i].points;
      if (points.length > 0) return points[points.length - 1];
    }
    return null;
  }

  private segmentsView(): ReadonlyArray<ReadonlyArray<RawPoint>> {
    if (this.viewVersion !== this.version) {
      const view: RawPoint[][] = [];
      for (const segment of this.segments) {
        if (segment.points.length === 0) continue;
        // 점은 뒤에만 붙으므로 수가 같으면 내용도 같다(틀린 첫 점을 버릴 땐 view를 비운다)
        if (segment.view === null || segment.view.length !== segment.points.length) segment.view = segment.points.slice();
        view.push(segment.view);
      }
      this.view = view;
      this.viewVersion = this.version;
    }
    return this.view;
  }
}

export function createRunTracker(options: RunTrackerOptions): RunTracker {
  return new Tracker(options);
}

/**
 * 원본 점·일시정지를 시각순으로 새 기록기에 다시 넣는다. 앱이 다시 켜졌을 때 이어서 push하려면 이것을 쓴다.
 * 점은 순서가 섞여 와도 되고(시각으로 정렬), 일시정지 안의 점은 버려진다.
 */
export function restoreRunTracker(
  rawPoints: ReadonlyArray<RawPoint>,
  pauses: ReadonlyArray<RunPause>,
  options: RunTrackerOptions,
): RunTracker {
  const tracker = createRunTracker(options);
  const points = rawPoints.slice().sort((a, b) => a.t - b.t);
  const ordered = pauses.filter(pause => Number.isFinite(pause.start)).sort((a, b) => a.start - b.start);
  let next = 0;
  const pushBefore = (limit: number) => {
    let end = next;
    while (end < points.length && points[end].t < limit) end += 1;
    if (end > next) tracker.push(points.slice(next, end));
    next = end;
  };
  for (const pause of ordered) {
    pushBefore(pause.start);
    tracker.pause(pause.start);
    if (pause.end === null) break;
    pushBefore(pause.end);
    tracker.resume(pause.end);
  }
  if (next < points.length) tracker.push(points.slice(next));
  return tracker;
}

/** 끝낼 때·복구할 때의 요약: 같은 기록기로 처음부터 다시 돌려 endedAt 시점의 snapshot을 낸다. */
export function summarizeTrack(
  rawPoints: ReadonlyArray<RawPoint>,
  pauses: ReadonlyArray<RunPause>,
  options: RunTrackerOptions & { endedAt: number },
): RunSnapshot {
  const tracker = restoreRunTracker(rawPoints, pauses, options);
  // 끝난 기록: 멈춘 것처럼 마지막 점까지 모두 넣는다(기록 화면도 끝내기 전에 멈춘다 — 그때 보인 숫자와 같다)
  if (!tracker.isPaused()) tracker.pause(options.endedAt);
  return tracker.snapshot(options.endedAt);
}
