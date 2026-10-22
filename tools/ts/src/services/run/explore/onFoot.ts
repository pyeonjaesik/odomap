// 걷기·달리기 구간 가리기(개척) — 순수 계산. 앱과 서버가 같은 파일을 쓴다:
//   앱  src/services/run/explore/onFoot.ts
//   서버 src/services/workouts/track/explore/onFoot.ts
// 두 파일은 글자 하나까지 같아야 한다(scripts/check-shared-run-code.sh). 다른 파일을 부르지 않는다.
//
// 개척은 걷고 뛴 길만 센다(2026-10-06 사용자: "걷는거랑 뛰는 것만 개척을 센다"). 다듬은 위치(runDistance PathSmoother의 표본)를 시각순으로
// 받아 표본마다 '발로 움직였는지'를 정한다. 쓰는 것:
//  - 속도: 표본을 가운데 둔 앞뒤 windowS초의 다듬은 속력 가운데값. vehicleSpeedMps보다 빠르면 탈것(사람은 25km/h를 30초 넘게 못 낸다).
//  - 걸음 수(있을 때): 같은 창의 분당 걸음. 움직이는데(moveSpeedMps 이상) 걸음이 stepMinCadence보다 적으면 탈것·자전거.
//  - 폰의 동작 인식(있을 때): 마지막으로 알려 준 것이 자동차·자전거이고 믿음이 보통 이상이면 탈것. 아이폰은 바뀔 때만 알려 주므로
//    다음 것이 올 때까지 이어진다고 본다(activityMaxAgeS까지).
//  걸음 수·동작 인식이 없으면(권한 없음·예전 빌드) 속도만 본다 — 그때는 버스가 막혀 느리게 가는 길을 걸은 길로 셀 수 있다.
// 판단은 한 표본씩 흔들리지 않게 묶는다: 바뀐 판단이 holdS초 동안 이어져야 바꾼다. 그래서 판단은 표본보다 windowS + holdS초 늦게
// 확정된다(기록 중 화면은 확정된 것까지 센다). 끝낼 때 flush()가 남은 표본을 그때까지 받은 것으로 확정한다.
// 같은 표본을 같은 순서로 넣으면 몇 개씩 묶어 넣어도 같은 답이다.
// 다시 시작(restart — 일시정지·필터를 새로 시작한 자리)과 gapS 넘는 끊김에서 창을 끊는다(끊긴 앞뒤를 섞어 판단하지 않는다).
// 서 있음(v3, 2026-10-08 — 사용자: "핸드폰을 건들지 않았어 … 들고 움직이지도 않았는데" 새로 걸은 길 0.03km): 걸음을 세는 기록인데 창 안의 걸음이
// stillMaxCadence보다 적고 걷는 빠르기(moveSpeedMps)보다 느리거나, 동작 인식이 '가만히'(믿음 보통 이상)이고 느리면 걷고 뛴 길이 아니다 — 실내에서
// 흔들리는 위치가 새 길로 쌓였다. 탈것과 달리 시간·거리 통계에도 넣지 않는다(kind 'still'). 거리 계산의 걸음 정지(runDistance StepLog)와 같은 뜻.
// 걸음으로 확인한 걸음(paced, 2026-10-10): 걸음을 세는 기록에서 창의 분당 걸음이 stillMaxCadence 이상인 걸어간 표본 — 서 있는 흔들림은 위에서
// 이미 빠지므로, 그물(exploreGraph v5)은 이런 걸음이면 짧은 왕복도 다시 지난 것으로 센다. 판단(onFoot·kind)·통계는 바꾸지 않는다.

export const ON_FOOT_RULES = {
  /** 계산이 바뀌면 올린다(v3 2026-10-08: 서 있음) */
  version: 3,
  /** 판단 창: 표본 앞뒤 이만큼(초) */
  windowS: 15,
  /** 바뀐 판단이 이만큼(초) 이어져야 바꾼다 */
  holdS: 15,
  /** 창의 가운데값 속력이 이보다 빠르면 탈것(m/s, 25km/h) */
  vehicleSpeedMps: 7,
  /** 걸음 수가 있을 때: 이 속력(m/s) 이상으로 움직이는데 */
  moveSpeedMps: 1.5,
  /** 분당 걸음이 이보다 적으면 탈것·자전거 */
  stepMinCadence: 30,
  /** 걸음을 세는 기록에서 창 안의 분당 걸음이 이보다 적고 moveSpeedMps보다 느리면 서 있음(v3) */
  stillMaxCadence: 12,
  /** 동작 인식은 이만큼(초) 지난 것까지 쓴다(아이폰은 바뀔 때만 알려 준다) */
  activityMaxAgeS: 1800,
  /** 걷기·달리기 나눔(m/s, 약 8km/h) — 통계·칼로리 */
  runSpeedMps: 2.2,
  /** 창의 가운데값 속력이 이보다 느리면 서 있던 것 — 걷기·달리기 시간·거리에 넣지 않는다(v2: 갤럭시에서 끝내기 전 100초 서 있던 것까지
   *  '걷기 8분'으로 셌다 — 기록 시간은 6:40). 판단(걷고 뛴 길인지)은 그대로다 */
  stillSpeedMps: 0.5,
  /** 앞 표본과 이보다 오래(초) 끊겼으면 창을 끊는다 */
  gapS: 60,
  /** 시간 통계: 표본 사이가 이보다 길면 이만큼(초)만 센다 */
  maxStepS: 10,
} as const;

/** 다듬은 위치 표본(runDistance SmoothedSample의 필요한 칸만). */
export type OnFootSample = {
  t: number;
  lat: number;
  lng: number;
  /** 다듬은 속력(m/s) */
  speed: number;
  weak: boolean;
  restart: boolean;
  /** 원본 점의 위치 오차(m, 모르면 null) — 판단에 쓰지 않고 그물(붙는 거리)로 넘긴다 */
  acc?: number | null;
};

export type OnFootKind = 'walk' | 'run' | 'vehicle' | 'still';

/** paced: 걸음으로 걷는 것을 확인한 표본(걸음을 세는 기록에서 창의 분당 걸음이 stillMaxCadence 이상 — 걷고 뛴 길일 때만) */
export type OnFootDecision = OnFootSample & { onFoot: boolean; kind: OnFootKind; paced: boolean };

/** 동작 인식 종류(iOS CMMotionActivity·Android Activity Recognition을 한 값으로) */
export type MotionActivity = 'still' | 'walk' | 'run' | 'bike' | 'vehicle' | 'unknown';

/** 기록 중 폰이 센 걸음·동작(세션 파일의 steps·act 줄). 시각순. */
export type OnFootMotion = {
  /** [ms epoch, 기록 시작부터 센 걸음(누계)] */
  steps?: ReadonlyArray<readonly [number, number]>;
  /** [ms epoch, 종류, 믿음 0 낮음 · 1 보통 · 2 높음] */
  activity?: ReadonlyArray<readonly [number, MotionActivity, number]>;
};

export type OnFootStats = {
  walkS: number;
  runS: number;
  vehicleS: number;
  walkM: number;
  runM: number;
  vehicleM: number;
};

const RAD = Math.PI / 180;
const METERS_PER_DEGREE = (6371008.8 * Math.PI) / 180;

function distanceM(a: { lat: number; lng: number }, b: { lat: number; lng: number }): number {
  const kx = METERS_PER_DEGREE * Math.cos(((a.lat + b.lat) / 2) * RAD);
  const dx = (b.lng - a.lng) * kx;
  const dy = (b.lat - a.lat) * METERS_PER_DEGREE;
  return Math.sqrt(dx * dx + dy * dy);
}

function median(values: number[]): number {
  if (values.length === 0) return 0;
  const sorted = values.slice().sort((x, y) => x - y);
  const mid = sorted.length >> 1;
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

/** 걸음 누계를 시각 at에서 읽는다(앞뒤 기록 사이는 곧게, 처음 기록 전은 null, 마지막 기록 뒤는 마지막 값). */
function stepsAt(steps: ReadonlyArray<readonly [number, number]>, at: number): number | null {
  if (steps.length === 0 || at < steps[0][0]) return null;
  let lo = 0;
  let hi = steps.length - 1;
  if (at >= steps[hi][0]) return steps[hi][1];
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (steps[mid][0] <= at) lo = mid;
    else hi = mid;
  }
  const [t0, n0] = steps[lo];
  const [t1, n1] = steps[hi];
  return t1 === t0 ? n1 : n0 + ((n1 - n0) * (at - t0)) / (t1 - t0);
}

/** at 시각에 유효한 동작 인식(activityMaxAgeS 안의 마지막 것). */
function activityAt(
  activity: ReadonlyArray<readonly [number, MotionActivity, number]>,
  at: number,
): readonly [number, MotionActivity, number] | null {
  let found: readonly [number, MotionActivity, number] | null = null;
  let lo = 0;
  let hi = activity.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (activity[mid][0] <= at) {
      found = activity[mid];
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  if (found === null || (at - found[0]) / 1000 > ON_FOOT_RULES.activityMaxAgeS) return null;
  return found;
}

/** 한 표본의 판단: 걷고 뛴 길 · 탈것 · 서 있음(v3) */
type Judged = 'foot' | 'vehicle' | 'still';

type Pending = OnFootSample & { raw: boolean | null; speedMedian: number; reason: Judged; paced: boolean };

/**
 * 표본을 받아 걷기·달리기인지 확정해 내보낸다(onDecision). 창을 쓰므로 표본보다 늦게 내보낸다.
 * motion은 기록 중에 늘어나는 배열을 그대로 넘겨도 된다(판단할 때 그 순간까지 받은 것을 본다).
 */
export class OnFootClassifier {
  private readonly rules = ON_FOOT_RULES;
  /** 지금 줄기(끊김 없이 이어진 표본) */
  private run: Pending[] = [];
  /** 줄기에서 raw를 정한 표본 수 */
  private rawUpTo = 0;
  /** 줄기에서 확정해 내보낸 표본 수 */
  private finalUpTo = 0;
  private state: boolean | null = null;
  private lastT = -Infinity;
  private prevEmitted: OnFootDecision | null = null;
  readonly stats: OnFootStats = { walkS: 0, runS: 0, vehicleS: 0, walkM: 0, runM: 0, vehicleM: 0 };

  constructor(
    private readonly motion: OnFootMotion,
    private readonly onDecision: (decision: OnFootDecision) => void,
  ) {}

  push(sample: OnFootSample): void {
    if (!Number.isFinite(sample.t) || sample.t <= this.lastT) return;
    const last = this.run[this.run.length - 1];
    const broken = sample.restart || (last !== undefined && (sample.t - last.t) / 1000 > this.rules.gapS);
    if (broken && this.run.length > 0) this.flushRun();
    this.lastT = sample.t;
    this.run.push({ ...sample, raw: null, speedMedian: 0, reason: 'foot', paced: false });
    this.advance(false);
  }

  /** 남은 표본을 모두 확정한다(기록을 끝낼 때). */
  flush(): void {
    this.flushRun();
  }

  private flushRun(): void {
    this.advance(true);
    this.run = [];
    this.rawUpTo = 0;
    this.finalUpTo = 0;
    this.state = null;
  }

  private advance(final: boolean): void {
    const run = this.run;
    if (run.length === 0) return;
    const newest = run[run.length - 1].t;
    const windowMs = this.rules.windowS * 1000;
    const holdMs = this.rules.holdS * 1000;
    // 1) 창이 다 찬(또는 끝낸) 표본의 판단
    while (this.rawUpTo < run.length && (final || run[this.rawUpTo].t + windowMs <= newest)) {
      this.decideRaw(this.rawUpTo);
      this.rawUpTo += 1;
    }
    // 2) 바뀐 판단은 holdS 동안 이어질 때만 받는다. 그 창의 판단이 다 모였는지: 끝냈거나, 판단한 표본 중 창 밖(뒤)의 것이 있다
    while (this.finalUpTo < this.rawUpTo) {
      const at = run[this.finalUpTo];
      const raw = at.raw as boolean;
      const ready = final || run[this.rawUpTo - 1].t > at.t + holdMs;
      if (this.state === null) {
        // 줄기의 처음: holdS 안의 판단 다수로 정한다
        if (!ready) break;
        let yes = 0;
        let total = 0;
        for (let i = this.finalUpTo; i < this.rawUpTo && run[i].t <= at.t + holdMs; i += 1) {
          total += 1;
          if (run[i].raw) yes += 1;
        }
        this.state = yes * 2 >= total;
      } else if (raw !== this.state) {
        // 바뀌려면 at.t부터 holdS 동안 판단이 모두 같아야 한다
        if (!ready) break;
        let same = true;
        for (let i = this.finalUpTo; i < this.rawUpTo && run[i].t <= at.t + holdMs; i += 1) {
          if (run[i].raw !== raw) {
            same = false;
            break;
          }
        }
        if (same) this.state = raw;
      }
      this.emit(at, this.state as boolean);
      this.finalUpTo += 1;
    }
  }

  private decideRaw(index: number): void {
    const run = this.run;
    const sample = run[index];
    const windowMs = this.rules.windowS * 1000;
    const speeds: number[] = [];
    let first = index;
    while (first > 0 && run[first - 1].t >= sample.t - windowMs) first -= 1;
    let last = index;
    while (last + 1 < run.length && run[last + 1].t <= sample.t + windowMs) last += 1;
    for (let i = first; i <= last; i += 1) speeds.push(Math.max(0, run[i].speed));
    const speedMedian = median(speeds);
    sample.speedMedian = speedMedian;
    // 창 동안 실제로 옮겨 간 빠르기(첫 표본 → 마지막 표본) — 흔들리는 위치는 순간 속력이 튀어도 제자리 둘레를 맴돈다(v3)
    const spanS = (run[last].t - run[first].t) / 1000;
    const netSpeed = spanS > 0 ? distanceM(run[first], run[last]) / spanS : 0;
    sample.reason = this.judge(sample, speedMedian, netSpeed, run[first].t, run[last].t);
    sample.raw = sample.reason === 'foot';
    const cadence = sample.raw ? this.cadenceOf(run[first].t, run[last].t) : null;
    sample.paced = cadence !== null && cadence >= this.rules.stillMaxCadence;
  }

  /** 창(fromT~toT)의 분당 걸음. 걸음을 세지 않는 기록·누계가 줄어든 창(기기를 다시 켜 센서가 처음부터)이면 null */
  private cadenceOf(fromT: number, toT: number): number | null {
    const steps = this.motion.steps;
    if (!steps || steps.length === 0 || !(toT > fromT)) return null;
    const from = stepsAt(steps, fromT);
    const to = stepsAt(steps, toT);
    if (from === null || to === null || to < from) return null;
    return ((to - from) / (toT - fromT)) * 60000;
  }

  private judge(sample: OnFootSample, speedMedian: number, netSpeed: number, fromT: number, toT: number): Judged {
    const rules = this.rules;
    if (speedMedian >= rules.vehicleSpeedMps) return 'vehicle';
    const activity = this.motion.activity;
    let activityNow: readonly [number, MotionActivity, number] | null = null;
    if (activity && activity.length > 0) {
      activityNow = activityAt(activity, sample.t);
      if (activityNow !== null && activityNow[2] >= 1) {
        if (activityNow[1] === 'vehicle' || activityNow[1] === 'bike') return 'vehicle';
      }
    }
    // 누계가 줄었으면(기기를 다시 켜 센서가 처음부터) 이 창은 걸음으로 판단하지 않는다(cadenceOf가 null)
    const cadence = this.cadenceOf(fromT, toT);
    if (cadence !== null) {
      // 서 있음(v3): 걸음이 거의 없고 실제로는 걷는 빠르기만큼도 옮겨 가지 않았다 — 흔들리는 위치(순간 속력이 튀어도)
      if (netSpeed < rules.moveSpeedMps && cadence < rules.stillMaxCadence) return 'still';
      // 탈것: 걷는 빠르기 넘게 실제로 옮겨 가는데 걸음이 적다(v3: 순간 속력만으로 보면 실내 흔들림이 '탈것'이 됐다)
      if (speedMedian >= rules.moveSpeedMps && netSpeed >= rules.moveSpeedMps && cadence < rules.stepMinCadence) return 'vehicle';
    }
    if (activityNow !== null && activityNow[2] >= 1 && activityNow[1] === 'still' && netSpeed < rules.moveSpeedMps) return 'still';
    return 'foot';
  }

  private emit(sample: Pending, onFoot: boolean): void {
    const rules = this.rules;
    // 걷고 뛴 길이 아니면 그 까닭(탈것·서 있음). 판단이 바뀌기를 기다리는 동안(holdS) 이 표본 자신은 걸음으로 보였으면 앞 표본의 까닭을 잇는다
    const offKind: OnFootKind = sample.reason === 'still' ? 'still' : sample.reason === 'vehicle' ? 'vehicle' : this.prevEmitted?.kind === 'still' ? 'still' : 'vehicle';
    const kind: OnFootKind = !onFoot ? offKind : sample.speedMedian >= rules.runSpeedMps ? 'run' : 'walk';
    const decision: OnFootDecision = {
      t: sample.t,
      lat: sample.lat,
      lng: sample.lng,
      speed: sample.speed,
      weak: sample.weak,
      restart: sample.restart,
      acc: sample.acc ?? null,
      onFoot,
      kind,
      paced: onFoot && sample.paced,
    };
    const previous = this.prevEmitted;
    const still = onFoot && sample.speedMedian < rules.stillSpeedMps;
    if (previous !== null && !still && !sample.restart && sample.t > previous.t && (sample.t - previous.t) / 1000 <= rules.gapS) {
      const seconds = Math.min((sample.t - previous.t) / 1000, rules.maxStepS);
      const meters = distanceM(previous, sample);
      if (kind === 'walk') {
        this.stats.walkS += seconds;
        this.stats.walkM += meters;
      } else if (kind === 'run') {
        this.stats.runS += seconds;
        this.stats.runM += meters;
      } else if (kind === 'vehicle') {
        this.stats.vehicleS += seconds;
        this.stats.vehicleM += meters;
      }
    }
    this.prevEmitted = decision;
    this.onDecision(decision);
  }
}
