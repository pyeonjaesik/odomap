// 러닝 거리·움직인 시간의 추정(순수 계산, React·네이티브 없음). runTrack이 일시정지로 나뉜 조각마다 PathSmoother 하나,
// 기록 전체에 Odometer 하나를 쓴다. 평가·근거는 docs/running-accuracy.md, 값은 runTrack.ts의 RUN_FILTER.
//
// 왜 선(받은 점)의 길이로 재지 않나: GPS 점을 그대로 이으면 점마다의 흔들림이 옆으로 더해져 거리가 늘어난다(옆 흔들림 e, 점 간격 s일 때
// 한 칸에 대략 e²/2s). 1초 간격에서 걷기(1.3m)는 흔들림 1~3m만 있어도 몇~수십 % 길어진다(합성 평가에서 예전 방식이 σ8m 걷기 +17%,
// 제자리 10분에 수백 m). 그래서 거리는 다음 순서로 다듬은 위치·속도로 잰다. 지도에 그리는 선은 runTrack이 받은 점 그대로 둔다.
//  1) 칼만 필터(등속 모델, 동·북 두 축): 위치는 기기가 준 오차 반경(acc)만큼만 믿고, 기기가 준 도플러 속도(speed·course)를 속도 측정으로
//     넣는다. 도플러 속도는 위치보다 훨씬 덜 흔들리고(0.1~0.5m/s) GPS 위치가 천천히 흘러가는 편향에 휘둘리지 않는다.
//     가속도 잡음은 속도의 제곱에 비례해 키운다(모퉁이에서 필요한 가속도 v²/r).
//  2) 예측에서 크게 벗어난 점(축마다 hardGate 표준편차 그리고 hardMinM 넘게)은 버리고, 조금 벗어난 점(robustGate)은 무게만 줄인다.
//     버린 점이 서로는 말이 되게 outlierRecoveryCount개 이어지면 필터 쪽이 틀렸다고 보고(첫 점이 틀렸거나 차를 탔다) 그 점들로 새로 시작한다.
//     선(그림)의 기준점과 상관없이 판단한다 — 선의 기준점이 튄 점이면 그 뒤 수십 초의 점이 모두 버려져 거리가 빠졌다.
//  3) 정지: 최근 stillCount개 도플러 속도의 평균이 stillSpeedMps보다 느리면 속도 0을 측정으로 넣고 위치를 얼린다(위치 측정을 쓰지 않고
//     불확실성도 키우지 않는다). 신호등 앞에서 흘러가는 GPS 위치가 거리로 붙지 않는다.
//  4) 고정 지연 스무딩(RTS, smoothingLag점): 필터는 지나간 점만 보고 판단해 모퉁이에서 늦게 꺾는다(안쪽으로 질러감). 몇 점 뒤까지 본 다음
//     확정하면 그 늦음이 없어진다. 확정 전의 마지막 몇 점은 snapshot이 그때그때 계산해 더한다(잠정, 상태는 안 바뀜).
//  5) Odometer: 도플러로 이어진 곳은 다듬은 속력을 시간으로 적분해 거리로, 그 밖(도플러 없는 기기·끊긴 곳)은 다듬은 위치의 곧은 선으로 잰다.
//     곧은 선은 stepMinM 넘게 움직였고 최근 stillWindowS초 동안 stillWindowSpeedMps 넘게 움직이고 있을 때만 더한다(도플러가 없을 때 멈춰
//     있는 동안 흘러가는 위치). 사람이 낼 수 없는 속도(차·지하철·틀린 위치에서 옮겨 감)로 이어진 곳은 더하지 않는다.
//  6) 걸음 정지(2026-10-08 — 걸음을 세는 기록, StepLog): 최근 stepStillWindowS초 동안 걸음이 거의 없고 걷는 빠르기보다 느리면 서 있는 것 —
//     그 사이는 거리·움직인 시간에 더하지 않고 기준점만 옮긴다. 실내의 와이파이 위치는 오차 25m 안에서도 몇 초마다 몇~십여 m씩 옮겨 가고 도플러
//     속도가 없어, 3)·5)의 정지 판단을 넘어 책상 위의 워치·폰이 '스스로 앞으로 가는' 거리가 붙었다.
// 도플러 속도는 진짜인지 확인한 뒤에만 쓴다(DopplerCheck): 가짜 위치 재생·일부 기기는 speed를 0이나 엉뚱한 값으로 준다.
//
// 같은 점을 같은 순서로 넣으면 늘 같은 답이다(기록 중 화면 = 끝낼 때 파일로 다시 계산한 값). 점을 몇 개씩 묶어 넣어도 같다.

import { haversineM, METERS_PER_DEGREE, metersPerDegreeLng } from './geo';
import type { RawPoint, RunFilter } from './runTrack';

const RAD = Math.PI / 180;

type Axis = { p: number; v: number };
/** 다듬은 위치·속도(동·북 m, m/s) */
type State = { x: number; y: number; vx: number; vy: number };
/** 한 축의 [[위치 분산, 공분산], [공분산, 속도 분산]]. 두 축은 측정 오차를 같게 두므로 공분산도 같다(2×2 하나로 두 축을 계산). */
type Cov = { a: number; b: number; c: number };

type Step = {
  t: number;
  /** 앞 단계에서 지난 시간(초). 필터를 새로 시작한 단계는 앞 위치에서 끊긴 시간 */
  gapS: number;
  priorX: Axis;
  priorY: Axis;
  priorP: Cov;
  postX: Axis;
  postY: Axis;
  postP: Cov;
  weak: boolean;
  restart: boolean;
  /** 도플러 속도(또는 정지의 속도 0)를 측정으로 넣었는지 */
  aided: boolean;
  /** 도플러로 멈춰 있다고 보고 위치를 얼렸는지 */
  still: boolean;
};

/**
 * 걸음 누계(발자취처럼 걸음을 세는 기록 — 세션 파일의 steps 줄: 폰은 CMPedometer·TYPE_STEP_COUNTER, 워치도 같은 모양). 시각순으로 받는다(앞선 시각은
 * 버린다). 누계가 줄면(기기를 다시 켜 센서가 처음부터 셈) 그 사이는 0걸음으로 보고 다음 값부터 이어 센다. 첫 기록보다 앞의 걸음은 모른다.
 * 같은 줄을 같은 차례로 넣으면 같은 답이다(기록 중엔 받은 만큼, 요약·다시 켤 때는 파일의 줄을 먼저 모두 넣는다).
 */
export class StepLog {
  private readonly times: number[] = [];
  /** 이어 센 누계(센서가 처음부터 다시 센 자리를 이었다) */
  private readonly counts: number[] = [];
  private lastRaw = 0;

  get size(): number {
    return this.times.length;
  }

  add(atMs: number, count: number): void {
    if (!Number.isFinite(atMs) || !Number.isFinite(count) || count < 0) return;
    const n = this.times.length;
    if (n > 0 && atMs < this.times[n - 1]) return;
    const value = n === 0 ? count : this.counts[n - 1] + (count >= this.lastRaw ? count - this.lastRaw : 0);
    this.lastRaw = count;
    if (n > 0 && atMs === this.times[n - 1]) {
      this.counts[n - 1] = value;
      return;
    }
    this.times.push(atMs);
    this.counts.push(value);
  }

  /** atMs의 누계(앞뒤 기록 사이는 곧게, 첫 기록 앞은 첫 값, 마지막 뒤는 마지막 값). 기록이 없으면 null */
  at(atMs: number): number | null {
    const n = this.times.length;
    if (n === 0) return null;
    if (atMs <= this.times[0]) return this.counts[0];
    if (atMs >= this.times[n - 1]) return this.counts[n - 1];
    let lo = 0;
    let hi = n - 1;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1; // eslint-disable-line no-bitwise
      if (this.times[mid] <= atMs) lo = mid;
      else hi = mid;
    }
    const t0 = this.times[lo];
    const t1 = this.times[hi];
    return t1 === t0 ? this.counts[hi] : this.counts[lo] + ((this.counts[hi] - this.counts[lo]) * (atMs - t0)) / (t1 - t0);
  }

  /**
   * 걸음으로 본 정지: 걸음을 센 기록이고(첫 걸음 기록 뒤), 옮겨 간 빠르기(speedMps — 부르는 쪽이 창 동안 실제로 옮겨 간 거리로 잰다: 흔들리는
   * 위치는 순간 속력이 튀어도 제자리 둘레를 맴돈다)가 stepStillMaxSpeedMps보다 느리고, atMs까지 stepStillWindowS초(기록 처음이면 첫 걸음 기록부터)
   * 동안 늘어난 걸음이 stepStillMinSteps보다 적다.
   */
  still(atMs: number, speedMps: number, f: RunFilter): boolean {
    const n = this.times.length;
    if (n === 0 || atMs < this.times[0] || !(speedMps < f.stepStillMaxSpeedMps)) return false;
    const from = Math.max(this.times[0], atMs - f.stepStillWindowS * 1000);
    const walked = (this.at(atMs) as number) - (this.at(from) as number);
    return walked < f.stepStillMinSteps;
  }
}

/** 다듬어 확정한(또는 잠정) 위치 하나. */
export type SmoothedSample = {
  t: number;
  lat: number;
  lng: number;
  /** 약한 신호에서 나온 위치인지(오차 반경이 크거나, 앞 위치에서 오래 끊겼거나, 크게 벗어난 점이 섞였거나) */
  weak: boolean;
  /** 바로 앞 위치에서 지난 시간(ms). 조각의 첫 위치면 0 */
  gapMs: number;
  /** 다듬은 속력(m/s) */
  speed: number;
  /** 도플러로 속도를 잡은 위치인지(앞 위치와의 사이를 속력으로 잴 수 있다) */
  aided: boolean;
  /** 필터를 새로 시작한 위치(앞 위치와 움직임 모델로 이어지지 않음) */
  restart: boolean;
  /** 도플러로 멈춰 있다고 본 위치 */
  still: boolean;
};

function accuracyOf(point: { acc: number | null }): number | null {
  return typeof point.acc === 'number' && Number.isFinite(point.acc) && point.acc >= 0 ? point.acc : null;
}

function finite(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function predictCov(P: Cov, dt: number, q: number): Cov {
  return {
    a: P.a + 2 * dt * P.b + dt * dt * P.c + (q * dt * dt * dt) / 3,
    b: P.b + dt * P.c + (q * dt * dt) / 2,
    c: P.c + q * dt,
  };
}

/**
 * 도플러 속도 확인. 기록(트래커) 하나에 하나. 위치로 잰 dopplerCheckWindowS초 이동과 도플러 속도로 잰 이동이 세 배 넘게 어긋나는 일이
 * dopplerMismatchLimit번(점마다 판단) 이어지면 그 뒤로 이 기록에서는 도플러를 쓰지 않는다. 다시 켜지 않는다(같은 점이면 같은 답이게 단순하게).
 */
export class DopplerCheck {
  usable = true;
  /** 창 안의 점. dopplerM·coveredS는 앞 점에서 이 점까지의 도플러 이동·시간(창의 첫 점 몫은 합에서 뺀다) */
  private readonly ring: { t: number; lat: number; lng: number; acc: number | null; dopplerM: number; coveredS: number }[] = [];
  private head = 0;
  private sumM = 0;
  private sumS = 0;
  private mismatch = 0;

  constructor(private readonly f: RunFilter) {}

  observe(point: RawPoint): void {
    if (!this.usable) return;
    const ring = this.ring;
    const previous = ring.length > this.head ? ring[ring.length - 1] : null;
    let dopplerM = 0;
    let coveredS = 0;
    if (previous !== null) {
      const dt = (point.t - previous.t) / 1000;
      if (finite(point.speed) && dt <= 5) {
        dopplerM = point.speed * dt;
        coveredS = dt;
      }
    }
    ring.push({ t: point.t, lat: point.lat, lng: point.lng, acc: accuracyOf(point), dopplerM, coveredS });
    this.sumM += dopplerM;
    this.sumS += coveredS;
    // 창보다 오래된 첫 점을 뺀다(두 점은 남긴다). 다음 점이 첫 점이 되면 그 점까지의 몫은 창 밖이다.
    const windowMs = this.f.dopplerCheckWindowS * 1000;
    while (ring.length - this.head > 2 && point.t - ring[this.head + 1].t >= windowMs) {
      this.head += 1;
      this.sumM -= ring[this.head].dopplerM;
      this.sumS -= ring[this.head].coveredS;
    }
    if (this.head > 64) {
      ring.splice(0, this.head);
      this.head = 0;
    }
    const first = ring[this.head];
    const span = point.t - first.t;
    if (span < windowMs * 0.9 || span > windowMs * 1.5) return;
    if (this.sumS < (span / 1000) * 0.8) return;
    const moved = haversineM(first, point);
    const acc = Math.max(first.acc ?? 0, accuracyOf(point) ?? 0);
    const floor = Math.max(this.f.dopplerCheckMinMoveM, 2.5 * acc);
    const tooSlow = moved >= floor && this.sumM < moved / 3;
    const tooFast = this.sumM >= floor && this.sumM > 3 * moved + floor;
    if (tooSlow || tooFast) {
      this.mismatch += 1;
      if (this.mismatch >= this.f.dopplerMismatchLimit) this.usable = false;
    } else {
      this.mismatch = 0;
    }
  }
}

/**
 * 한 조각(일시정지 사이)의 위치를 다듬는다. add로 점을 시각순으로 넣고, 확정된 위치는 onSample로 내보낸다. tail()은 아직 확정하지 않은
 * 마지막 몇 점의 지금 추정(잠정, 상태를 바꾸지 않음), close()는 남은 점을 모두 확정한다(조각이 끝났을 때).
 */
export class PathSmoother {
  private originLat = 0;
  private originLng = 0;
  private kx = 0;
  private hasOrigin = false;
  private window: Step[] = [];
  private last: Step | null = null;
  /** 최근 stillCount개의 도플러 속도(없으면 null) */
  private speeds: (number | null)[] = [];
  private outliers: RawPoint[] = [];
  private restarting = false;
  private lastEmittedT: number | null = null;
  /** 필터에 넣은 마지막 점의 시각(끊긴 뒤 새로 시작한 곳을 약한 구간으로 표시할 때 앞 시각) */
  private lastSeenT: number | null = null;
  /** 도플러로 속도를 잡은 마지막 점의 시각 */
  private lastAidedT: number | null = null;

  constructor(
    private readonly f: RunFilter,
    private readonly maxSpeedMps: number,
    private readonly doppler: DopplerCheck,
    private readonly onSample: (sample: SmoothedSample) => void,
  ) {}

  add(point: RawPoint): void {
    const f = this.f;
    const acc = accuracyOf(point);
    const last = this.last;
    const gapS = last ? (point.t - last.t) / 1000 : 0;
    if (last === null || gapS > f.gapResetS) {
      // 처음이거나 오래 끊겼다: 오차가 작은 점에서 새로 시작한다(끊긴 사이를 움직임 모델로 잇지 않는다 — 몇 분 끊긴 뒤 반대로 가면 고리가
      // 생긴다). 끊긴 사이의 거리는 Odometer가 곧은 선으로 잇는다(사람이 낼 수 있는 속도일 때만).
      if (acc !== null && acc > f.firstPointMaxAccuracyM) return;
      this.flushWindow();
      this.start(point, last !== null);
      return;
    }
    // 튄 점은 버린다: 예측에서 아주 멀거나(통계), 직전 위치에서 사람이 낼 수 없는 속도로 옮겨 갔거나(물리 — 시작 직후처럼 속도를 모르면
    // 예측의 불확실성이 커서 통계로는 300m 튄 첫 점도 몇 초 뒤엔 받아들였다). 버린 점이 서로 말이 되게 이어지면 필터가 틀렸다.
    const dt = Math.max(gapS, 0.05);
    const movingP = predictCov(last.postP, dt, this.processNoise(last));
    const { zx, zy } = this.toLocal(point);
    const ex = zx - (last.postX.p + last.postX.v * dt);
    const ey = zy - (last.postY.p + last.postY.v * dt);
    const r = this.positionVariance(acc);
    const innovation = Math.hypot(ex, ey);
    const statistical = innovation > f.hardMinM && innovation * innovation > 2 * f.hardGate * f.hardGate * (movingP.a + r);
    const jump = Math.hypot(zx - last.postX.p, zy - last.postY.p) - 3 * Math.sqrt(r + last.postP.a);
    if (!this.restarting && (statistical || jump > this.maxSpeedMps * dt)) {
      this.onOutlier(point);
      return;
    }
    if (this.outliers.length > 0) this.outliers = [];
    this.update(point, last, dt, gapS, movingP, zx, zy, r, acc, innovation);
  }

  /** 남은 점을 모두 확정한다(조각이 끝났을 때). */
  close(): void {
    this.flushWindow();
  }

  /** 마지막 점에서 windowMs 안에 도플러로 속도를 잡은 점이 있었는지(이 조각에서). */
  aidedWithin(windowMs: number): boolean {
    return this.lastAidedT !== null && this.last !== null && this.last.t - this.lastAidedT <= windowMs;
  }

  /** 아직 확정하지 않은 점들의 지금 추정(상태를 바꾸지 않는다). */
  tail(): SmoothedSample[] {
    if (this.window.length === 0) return [];
    const smoothed = this.backward();
    let previousT = this.lastEmittedT;
    return this.window.map((step, i) => {
      const sample = this.sampleOf(step, smoothed[i], previousT);
      previousT = step.t;
      return sample;
    });
  }

  // ── 안쪽

  private toLocal(point: RawPoint): { zx: number; zy: number } {
    if (!this.hasOrigin) {
      this.hasOrigin = true;
      this.originLat = point.lat;
      this.originLng = point.lng;
      this.kx = metersPerDegreeLng(point.lat);
    }
    return { zx: (point.lng - this.originLng) * this.kx, zy: (point.lat - this.originLat) * METERS_PER_DEGREE };
  }

  private positionVariance(acc: number | null): number {
    const f = this.f;
    const sigma = Math.min(f.maxPositionSigmaM, Math.max(f.minPositionSigmaM, (acc ?? f.defaultAccuracyM) * f.accuracyToSigma));
    return sigma * sigma;
  }

  /** 가속도 잡음 밀도: 모퉁이에서 필요한 가속도(v²/r)가 속도의 제곱에 비례하므로 지금 속도로 정한다. */
  private processNoise(state: Step): number {
    const v2 = state.postX.v * state.postX.v + state.postY.v * state.postY.v;
    return this.f.processNoise + this.f.processNoiseSpeedK * v2;
  }

  /**
   * 도플러 속도(동·북, m/s)와 축마다의 분산. 방향(course)은 느리면 믿을 수 없어, dopplerMinSpeedMps보다 느리면 '방향 모를 느린 속도'로
   * 본다: 0을 중심으로 속력만큼 퍼진 측정(축마다 분산 속력²/2 + 오차²). 그보다 빠른데 방향이 없으면 쓰지 않는다(0 쪽으로 끌어 거리가
   * 크게 줄었다 — 속력만 있고 방향이 없는 점은 기기에 따라 온다).
   */
  private dopplerVelocity(point: RawPoint): { vx: number; vy: number; r: number } | null {
    const f = this.f;
    if (!(f.dopplerAiding > 0) || !this.doppler.usable) return null;
    const speed = point.speed;
    if (!finite(speed) || speed < 0) return null;
    const sacc = finite(point.sacc) && point.sacc > 0 ? point.sacc : f.dopplerDefaultSigmaMps;
    const base = sacc * sacc + f.dopplerBodySigmaMps * f.dopplerBodySigmaMps;
    if (speed < f.dopplerMinSpeedMps) return { vx: 0, vy: 0, r: base + (speed * speed) / 2 };
    if (!finite(point.course) || point.course < 0) return null;
    const cross = speed * f.dopplerCourseSigmaDeg * RAD;
    return {
      vx: speed * Math.sin(point.course * RAD),
      vy: speed * Math.cos(point.course * RAD),
      r: base + cross * cross,
    };
  }

  /** 도플러로 본 정지: 최근 stillCount개 속도가 모두 있고, 평균이 stillSpeedMps보다 느리고, 가장 빠른 것도 stillMaxSpeedMps 아래. */
  private trackStill(point: RawPoint): boolean {
    const f = this.f;
    const usable = f.dopplerAiding > 0 && this.doppler.usable && finite(point.speed) && point.speed >= 0;
    this.speeds.push(usable ? (point.speed as number) : null);
    const count = Math.max(1, Math.floor(f.stillCount));
    while (this.speeds.length > count) this.speeds.shift();
    if (this.speeds.length < count) return false;
    let sum = 0;
    for (const speed of this.speeds) {
      if (speed === null || speed >= f.stillMaxSpeedMps) return false;
      sum += speed;
    }
    return sum / count < f.stillSpeedMps;
  }

  private start(point: RawPoint, afterGap: boolean): void {
    const f = this.f;
    this.doppler.observe(point);
    const { zx, zy } = this.toLocal(point);
    const acc = accuracyOf(point);
    const r = this.positionVariance(acc);
    const velocity = this.dopplerVelocity(point);
    this.outliers = [];
    this.speeds = [];
    this.trackStill(point);
    const x = { p: zx, v: velocity?.vx ?? 0 };
    const y = { p: zy, v: velocity?.vy ?? 0 };
    const P: Cov = { a: r, b: 0, c: velocity ? velocity.r : f.initialSpeedSigmaMps * f.initialSpeedSigmaMps };
    this.push({
      t: point.t,
      gapS: this.lastSeenT === null ? 0 : (point.t - this.lastSeenT) / 1000,
      priorX: x,
      priorY: y,
      priorP: P,
      postX: x,
      postY: y,
      postP: P,
      weak: afterGap || (acc ?? 0) > f.weakAccuracyM,
      restart: true,
      aided: velocity !== null,
      still: false,
    });
  }

  private update(
    point: RawPoint,
    last: Step,
    dt: number,
    gapS: number,
    movingP: Cov,
    zx: number,
    zy: number,
    r: number,
    acc: number | null,
    innovation: number,
  ): void {
    const f = this.f;
    this.doppler.observe(point);
    // 멈춰 있으면(도플러) 위치를 얼린다: 위치 측정을 쓰지 않고 불확실성도 키우지 않는다. 다만 위치가 크게 벗어나면(도플러가 틀렸거나
    // 다시 움직임) 얼리지 않는다.
    const still = this.trackStill(point) && innovation < Math.max(f.stillBreakM, 3 * Math.sqrt(movingP.a + r));
    const priorP = still ? predictCov(last.postP, dt, 0) : movingP;
    const priorX = { p: last.postX.p + last.postX.v * dt, v: last.postX.v };
    const priorY = { p: last.postY.p + last.postY.v * dt, v: last.postY.v };
    let x = priorX;
    let y = priorY;
    let P = priorP;
    const gate2 = 2 * f.robustGate * f.robustGate;
    let downWeighted = false;
    if (!still) {
      // 위치: 잔차가 크면 오차를 키워 무게를 줄인다(Huber)
      const ex = zx - x.p;
      const ey = zy - y.p;
      const e2 = ex * ex + ey * ey;
      let rUse = r;
      if (e2 > gate2 * (P.a + r)) {
        rUse = Math.max(r, e2 / gate2 - P.a);
        downWeighted = true;
      }
      const s = P.a + rUse;
      const k0 = P.a / s;
      const k1 = P.b / s;
      x = { p: x.p + k0 * ex, v: x.v + k1 * ex };
      y = { p: y.p + k0 * ey, v: y.v + k1 * ey };
      P = { a: (1 - k0) * P.a, b: (1 - k0) * P.b, c: P.c - k1 * P.b };
    }
    const velocityUpdate = (vx: number, vy: number, rv: number) => {
      const dx = vx - x.v;
      const dy = vy - y.v;
      const d2 = dx * dx + dy * dy;
      if (d2 > 2 * f.hardGate * f.hardGate * (P.c + rv)) return; // 말이 안 되는 속도는 버린다
      let rv2 = rv;
      if (d2 > gate2 * (P.c + rv2)) rv2 = Math.max(rv2, d2 / gate2 - P.c);
      const s = P.c + rv2;
      const k0 = P.b / s;
      const k1 = P.c / s;
      x = { p: x.p + k0 * dx, v: x.v + k1 * dx };
      y = { p: y.p + k0 * dy, v: y.v + k1 * dy };
      P = { a: P.a - k0 * P.b, b: (1 - k1) * P.b, c: (1 - k1) * P.c };
    };
    let aided = still;
    if (still) velocityUpdate(0, 0, f.stillSigmaMps * f.stillSigmaMps);
    else {
      const velocity = this.dopplerVelocity(point);
      if (velocity) {
        velocityUpdate(velocity.vx, velocity.vy, velocity.r);
        aided = true;
      }
    }
    this.push({
      t: point.t,
      gapS,
      priorX,
      priorY,
      priorP,
      postX: x,
      postY: y,
      postP: P,
      weak: (acc ?? 0) > f.weakAccuracyM || gapS > f.weakGapS || downWeighted,
      restart: false,
      aided,
      still,
    });
  }

  private onOutlier(point: RawPoint): void {
    const previous = this.outliers[this.outliers.length - 1];
    const consistent =
      previous !== undefined && haversineM(previous, point) / Math.max((point.t - previous.t) / 1000, 1) <= this.maxSpeedMps;
    if (consistent) this.outliers.push(point);
    else this.outliers = [point];
    if (this.outliers.length < Math.max(2, Math.floor(this.f.outlierRecoveryCount))) return;
    // 필터가 틀렸다: 버린 점들로 새로 시작한다. 그 사이를 이을지는 Odometer가 속도로 판단한다.
    const run = this.outliers;
    this.outliers = [];
    this.flushWindow();
    this.last = null;
    this.restarting = true;
    for (const p of run) {
      if (this.last === null) {
        if ((accuracyOf(p) ?? 0) > this.f.firstPointMaxAccuracyM) continue;
        this.start(p, true);
      } else this.add(p);
    }
    this.restarting = false;
  }

  private push(step: Step): void {
    this.window.push(step);
    this.last = step;
    this.lastSeenT = step.t;
    if (step.aided) this.lastAidedT = step.t;
    if (this.window.length > this.f.smoothingLag) {
      this.emit(this.window[0], this.backward(true)[0]);
      this.window.shift();
    }
  }

  private flushWindow(): void {
    if (this.window.length === 0) return;
    const smoothed = this.backward();
    this.window.forEach((step, i) => this.emit(step, smoothed[i]));
    this.window = [];
  }

  private emit(step: Step, state: State): void {
    const sample = this.sampleOf(step, state, this.lastEmittedT);
    this.lastEmittedT = step.t;
    this.onSample(sample);
  }

  private sampleOf(step: Step, state: State, previousT: number | null): SmoothedSample {
    return {
      t: step.t,
      lat: this.originLat + state.y / METERS_PER_DEGREE,
      lng: this.originLng + (this.kx === 0 ? 0 : state.x / this.kx),
      weak: step.weak,
      gapMs: previousT === null ? 0 : step.t - previousT,
      speed: Math.hypot(state.vx, state.vy),
      aided: step.aided,
      restart: step.restart,
      still: step.still,
    };
  }

  /**
   * RTS 뒤로 훑기: 창의 마지막(필터 값 그대로)부터 첫 점까지 다듬은 위치·속도. 새로 시작한 단계에서는 끊는다.
   * onlyFirst면 첫 점 하나만 돌려준다(점마다 하나씩 확정할 때 — 긴 기록을 다시 돌릴 때 객체를 만들지 않게).
   */
  private backward(onlyFirst = false): State[] {
    const w = this.window;
    const n = w.length;
    const out: State[] = onlyFirst ? [] : new Array(n);
    const end = w[n - 1];
    let sxp = end.postX.p;
    let sxv = end.postX.v;
    let syp = end.postY.p;
    let syv = end.postY.v;
    if (!onlyFirst) out[n - 1] = { x: sxp, y: syp, vx: sxv, vy: syv };
    for (let k = n - 2; k >= 0; k -= 1) {
      const cur = w[k];
      const next = w[k + 1];
      const Q = next.priorP;
      const det = Q.a * Q.c - Q.b * Q.b;
      if (next.restart || !(det > 0)) {
        sxp = cur.postX.p;
        sxv = cur.postX.v;
        syp = cur.postY.p;
        syv = cur.postY.v;
      } else {
        const dt = Math.max(next.gapS, 0.05);
        const P = cur.postP;
        // C = P Fᵀ Q⁻¹, F = [[1, dt], [0, 1]]
        const m00 = P.a + P.b * dt;
        const m01 = P.b;
        const m10 = P.b + P.c * dt;
        const m11 = P.c;
        const i00 = Q.c / det;
        const i01 = -Q.b / det;
        const i11 = Q.a / det;
        const c00 = m00 * i00 + m01 * i01;
        const c01 = m00 * i01 + m01 * i11;
        const c10 = m10 * i00 + m11 * i01;
        const c11 = m10 * i01 + m11 * i11;
        const dxp = sxp - next.priorX.p;
        const dxv = sxv - next.priorX.v;
        const dyp = syp - next.priorY.p;
        const dyv = syv - next.priorY.v;
        sxp = cur.postX.p + c00 * dxp + c01 * dxv;
        sxv = cur.postX.v + c10 * dxp + c11 * dxv;
        syp = cur.postY.p + c00 * dyp + c01 * dyv;
        syv = cur.postY.v + c10 * dyp + c11 * dyv;
      }
      if (!onlyFirst) out[k] = { x: sxp, y: syp, vx: sxv, vy: syv };
    }
    if (onlyFirst) out.push({ x: sxp, y: syp, vx: sxv, vy: syv });
    return out;
  }
}

export type OdometerCrossing = {
  /** splitM(1km)을 지난 순간의 누적 움직인 시간(ms) */
  movingMs: number;
  /** 그 시각(ms) */
  t: number;
};

export type OdometerSpan = { startT: number; endT: number; startD: number; endD: number };

type OpenSpan = { startT: number; startD: number; lastT: number; lastD: number };

/**
 * 다듬은 위치를 받아 거리·움직인 시간·1km 지점·약한 구간을 센다. snapshot이 아직 확정하지 않은 마지막 점들을 더해 볼 때는 복사본(clone)에 넣는다.
 */
export class Odometer {
  distance = 0;
  movingMs = 0;
  crossings: OdometerCrossing[] = [];
  spans: OdometerSpan[] = [];
  private anchor: SmoothedSample | null = null;
  private maxGapMs = 0;
  /** 기준점 뒤로 다듬은 속력을 시간으로 적분한 거리·그중 움직인 시간, 그 사이가 모두 도플러로 이어졌는지 */
  private pendingM = 0;
  private pendingMovingMs = 0;
  private pendingAided = true;
  private previous: SmoothedSample | null = null;
  /** 최근 stillWindowS초의 위치(도플러가 없을 때의 정지 판단) */
  private recent: SmoothedSample[] = [];
  private open: OpenSpan | null = null;

  constructor(
    private readonly f: RunFilter,
    private readonly splitM: number,
    private readonly maxSpeedMps: number,
    /** 걸음을 세는 기록이면 그 걸음(서 있는지 — 6). 같은 것을 복사본과 같이 본다 */
    private readonly steps: StepLog | null = null,
  ) {}

  clone(): Odometer {
    const copy = new Odometer(this.f, this.splitM, this.maxSpeedMps, this.steps);
    copy.distance = this.distance;
    copy.movingMs = this.movingMs;
    copy.crossings = this.crossings.slice();
    copy.spans = this.spans.slice();
    copy.anchor = this.anchor;
    copy.maxGapMs = this.maxGapMs;
    copy.pendingM = this.pendingM;
    copy.pendingMovingMs = this.pendingMovingMs;
    copy.pendingAided = this.pendingAided;
    copy.previous = this.previous;
    copy.recent = this.recent.slice();
    copy.open = this.open ? { ...this.open } : null;
    return copy;
  }

  /** 새 조각(다시 시작): 앞 조각과 잇지 않는다. */
  startPiece(): void {
    this.closeSpan();
    this.anchor = null;
    this.maxGapMs = 0;
    this.recent = [];
    this.previous = null;
    this.resetPending();
  }

  feed(sample: SmoothedSample): void {
    const f = this.f;
    const before = this.distance;
    const windowMs = f.stillWindowS * 1000;
    // 최근 창 안의 위치만 둔다(끊긴 뒤에는 창이 비어 '멈춰 있음'을 판단하지 않는다 — 끊기기 전 위치와 비교하면 다시 움직인 첫 몇 초가
    // 멈춤으로 보였다)
    this.recent.push(sample);
    while (this.recent.length > 1 && sample.t - this.recent[0].t > windowMs) this.recent.shift();
    // 앞 위치에서 여기까지 다듬은 속력의 적분(사다리꼴, 멈춘 위치는 0). 끊겼거나 새로 시작했거나 도플러가 없던 곳이 섞이면 그 사이는
    // 곧은 선으로 잰다.
    const previous = this.previous;
    this.previous = sample;
    if (previous !== null) {
      const dt = (sample.t - previous.t) / 1000;
      const speed = ((previous.still ? 0 : previous.speed) + (sample.still ? 0 : sample.speed)) / 2;
      this.pendingM += speed * dt;
      if (speed >= f.movingMinSpeedMps) this.pendingMovingMs += dt * 1000;
      if (!(sample.aided && previous.aided) || sample.restart || dt > f.speedIntegralMaxGapS) this.pendingAided = false;
    }
    const anchor = this.anchor;
    if (anchor === null) {
      this.anchor = sample;
      this.maxGapMs = 0;
      this.resetPending();
    } else {
      if (sample.gapMs > this.maxGapMs) this.maxGapMs = sample.gapMs;
      if (haversineM(anchor, sample) >= f.stepMinM && sample.t > anchor.t) this.step(anchor, sample, false);
    }
    // 약한 구간
    const open = this.open;
    if (sample.weak) {
      if (open === null) {
        const startT = sample.gapMs > 0 ? sample.t - sample.gapMs : sample.t;
        this.open = { startT, startD: before, lastT: sample.t, lastD: this.distance };
      } else {
        open.lastT = sample.t;
        open.lastD = this.distance;
      }
    } else if (open !== null && sample.t - open.lastT > f.weakMergeS * 1000) {
      this.closeSpan();
    }
  }

  /**
   * 기준점 뒤로 아직 stepMinM이 안 된 마지막 조금을 더한다. snapshot의 복사본에서만 부른다(끝낼 때의 숫자가 마지막 걸음까지 담게,
   * 상태는 그대로 두므로 다음 점이 오면 원래대로 이어 간다).
   */
  addOpenStep(): void {
    const anchor = this.anchor;
    const last = this.previous;
    if (anchor === null || last === null || !(last.t > anchor.t)) return;
    this.step(anchor, last, true);
  }

  /** 열려 있는 약한 구간까지 포함한 목록 */
  allSpans(): OdometerSpan[] {
    const open = this.open;
    if (open === null || open.lastT - open.startT < this.f.weakMinSpanS * 1000) return this.spans;
    return [...this.spans, { startT: open.startT, endT: open.lastT, startD: open.startD, endD: open.lastD }];
  }

  // ── 안쪽

  /** 기준점에서 sample까지를 잰다. open: 끝낼 때 남은 조금(멈춰 있는 중이면 더하지 않는다) */
  private step(anchor: SmoothedSample, sample: SmoothedSample, open: boolean): void {
    const f = this.f;
    // 걸음이 없는 동안(6): 거리·움직인 시간에 넣지 않고 기준점만 옮긴다(다시 걸으면 그 자리부터 센다). 빠르기는 최근 창(recent) 동안 옮겨 간 거리로 —
    // 실내 위치는 한 번에 십여 m씩 튀어 순간 속력이 걷는 빠르기를 넘는다(합성 실내 흔들림: 순간 속력으로 보면 4분에 110m가 샜다)
    if (this.steps !== null && this.steps.size > 0) {
      const oldest = this.recent[0];
      const spanS = oldest !== undefined ? (sample.t - oldest.t) / 1000 : 0;
      const netSpeed = spanS >= 5 ? haversineM(oldest, sample) / spanS : sample.still ? 0 : sample.speed;
      if (this.steps.still(sample.t, netSpeed, f)) {
        if (!open) this.skip(sample);
        return;
      }
    }
    const dtMs = sample.t - anchor.t;
    const seconds = dtMs / 1000;
    if (this.pendingAided && f.speedIntegral > 0) {
      // 도플러로 이어진 곳: 다듬은 속력의 적분. 속력은 위치 보정(편향을 따라가는 몫)을 담지 않아 몇 분씩 한쪽으로 흐르는 편향에 거리가
      // 늘거나 줄지 않는다. 멈춤은 도플러가 이미 가렸다(멈춘 위치는 0).
      if (open && this.pendingM <= 0) return;
      if (this.pendingM / seconds > this.maxSpeedMps) this.skip(sample);
      else this.add(anchor, sample, this.pendingM, this.pendingMovingMs);
      return;
    }
    const d = haversineM(anchor, sample);
    const speed = d / seconds;
    // 끝낼 때의 남은 조금은 걷는 속도 이상으로 움직이는 중일 때만(멈춘 동안 흘러간 위치가 화면 숫자에 붙었다 떨어지지 않게)
    if (open && speed < f.movingMinSpeedMps) return;
    if (speed > this.maxSpeedMps) {
      // 사람이 낼 수 없는 속도(차·지하철, 또는 틀린 위치에서 옮겨 감): 거리·시간에 넣지 않고 기준점만 옮긴다
      if (!open) this.skip(sample);
      return;
    }
    // 최근 창 동안 거의 제자리였으면(멈춰 있는 동안 흘러간 위치) 아직 더하지 않는다. 다시 움직이면 기준점에서 한 번에 잇는다.
    const windowMs = f.stillWindowS * 1000;
    const oldest = this.recent[0];
    const recentSpan = sample.t - oldest.t;
    const settled = recentSpan >= windowMs * 0.8 && haversineM(oldest, sample) < (f.stillWindowSpeedMps * recentSpan) / 1000;
    if (settled) return;
    // 위치가 끊긴 사이(터널 등)도 사람이 움직일 만한 속도로 이어졌으면 움직인 시간에 넣는다(movingMaxGapS까지)
    const moving = speed >= f.movingMinSpeedMps && this.maxGapMs <= f.movingMaxGapS * 1000;
    this.add(anchor, sample, d, moving ? dtMs : 0);
  }

  /** 기준점에서 sample까지 meters·movingAdd(ms)를 더하고 1km 지점을 센다. */
  private add(anchor: SmoothedSample, sample: SmoothedSample, meters: number, movingAdd: number): void {
    const dtMs = sample.t - anchor.t;
    let next = (this.crossings.length + 1) * this.splitM;
    while (this.distance + meters >= next) {
      const fraction = meters > 0 ? (next - this.distance) / meters : 1;
      this.crossings.push({ movingMs: this.movingMs + fraction * movingAdd, t: anchor.t + fraction * dtMs });
      next += this.splitM;
    }
    this.distance += meters;
    this.movingMs += movingAdd;
    this.anchor = sample;
    this.maxGapMs = 0;
    this.resetPending();
  }

  /** 거리에 넣지 않고 기준점만 옮긴다. */
  private skip(sample: SmoothedSample): void {
    this.anchor = sample;
    this.maxGapMs = 0;
    this.resetPending();
  }

  private resetPending(): void {
    this.pendingM = 0;
    this.pendingMovingMs = 0;
    this.pendingAided = true;
  }

  private closeSpan(): void {
    const open = this.open;
    this.open = null;
    if (open === null || open.lastT - open.startT < this.f.weakMinSpanS * 1000) return;
    this.spans.push({ startT: open.startT, endT: open.lastT, startD: open.startD, endD: open.lastD });
  }
}
