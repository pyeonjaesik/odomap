// 러닝 오르막(고도 상승)의 계산(순수 계산, React·네이티브 없음). runTrack이 일시정지로 나뉜 조각마다 ElevationPiece 하나를 쓴다.
//
// GPS 고도는 수평 위치보다 1.5~2배 흔들리고(폰은 보통 σ 5~15m), 몇 분에 걸쳐 천천히 오르내리며, 가끔 수십 m 튄다. 받은 점(선)의
// 고도를 5점 평균 + 3m 이력으로 더하던 예전 방식은 평지 5km에서도 오르막이 40~190m 나왔다(docs/running-accuracy.md, 고도 표).
// 그래서 이렇게 잰다.
//  1) 선의 점이 아니라 쓸 수 있는 모든 점(정확도 기준 통과)의 고도를 쓰고, 기기가 준 고도 오차(vacc)가 maxVerticalAccuracyM보다 큰 점은
//     고도만 뺀다.
//  2) 가운데 맞춘 중앙값(elevationMedianPoints점)으로 튄 값을 지우고, 이동 평균(elevationSmoothingPoints점)으로 빠른 흔들림을 줄인다.
//  3) 이력(zigzag): 반대로 기준만큼 움직여야 방향이 바뀐 것으로 보고, 오르막(골→마루)만 더한다. 기준은 고도 오차에 맞춰
//     max(elevationHysteresisM, elevationVerticalFactor × 고도 표준편차). 천천히 흐르는 오차가 기준보다 작게 오르내리면 더해지지 않는다.
//     고도 오차를 모르는 점(가져온 기록·서버 점)은 defaultVerticalAccuracyM으로 본다.
// 평균 창이 다 차야 그 점의 값이 확정된다. 아직 확정하지 않은 끝의 점들은 잘린 창으로 잠정 계산해 snapshot에 더한다(상태는 안 바뀜).
// 기록 중 snapshot은 점마다(1초) 1km 지점마다 고도를 묻는다. 예전엔 물을 때마다 확정된 배열 전체를 복사해(series의 concat, tail의
// 중앙값 복사) 기록이 길수록 점마다 일이 늘었다(10km면 점 하나에 수십만 칸 복사). 지금은 복사하지 않고(altitudeAt·firstAltitude·
// lastAltitude가 확정분과 끝의 잠정분을 이어 본다) 끝의 잠정분은 점이 더 올 때까지 한 번만 센다 — 값은 series + altitudeAtTime과 같다.
// 남은 한계: 기압계 없이 GPS 고도만으로는 몇 분에 걸친 흐름과 진짜 완만한 언덕을 가르지 못한다. 정확한 값은 지형 고도(DEM)로 다시 재는
// 것이다(Strava는 기압계 없는 기록의 고도를 지형 고도로 바꾼다).

import type { RunFilter } from './runTrack';

type Zigzag = { started: boolean; dir: -1 | 0 | 1; lo: number; hi: number; gain: number };

function newZigzag(): Zigzag {
  return { started: false, dir: 0, lo: 0, hi: 0, gain: 0 };
}

/** 기준(threshold)만큼 반대로 움직여야 방향이 바뀐 것으로 보고, 오르막(골→마루)만 더한다. */
function zigzagFeed(z: Zigzag, h: number, threshold: number): void {
  if (!z.started) {
    z.started = true;
    z.lo = h;
    z.hi = h;
    return;
  }
  if (z.dir === 0) {
    if (h > z.hi) z.hi = h;
    if (h < z.lo) z.lo = h;
    if (h - z.lo >= threshold) {
      z.dir = 1;
      z.hi = h;
    } else if (z.hi - h >= threshold) {
      z.dir = -1;
      z.lo = h;
    }
  } else if (z.dir === 1) {
    if (h > z.hi) z.hi = h;
    else if (z.hi - h >= threshold) {
      z.gain += z.hi - z.lo;
      z.dir = -1;
      z.lo = h;
    }
  } else if (h < z.lo) {
    z.lo = h;
  } else if (h - z.lo >= threshold) {
    z.dir = 1;
    z.hi = h;
  }
}

/** 확정된 오르막 + 지금 오르는 중인 오르막(이미 기준을 넘었다). */
function zigzagTotal(z: Zigzag): number {
  return z.gain + (z.dir === 1 ? z.hi - z.lo : 0);
}

function median(values: number[]): number {
  const sorted = values.slice().sort((a, b) => a - b);
  const mid = sorted.length >> 1; // eslint-disable-line no-bitwise
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

/** 다듬은 고도(시각순). 확정된 것과 아직 잠정인 끝의 것을 함께 돌려준다. */
export type ElevationSeries = { t: ReadonlyArray<number>; h: ReadonlyArray<number> };

/** 한 조각(일시정지 사이)의 고도. add로 시각순으로 넣는다. */
export class ElevationPiece {
  private readonly rawT: number[] = [];
  private readonly rawH: number[] = [];
  private readonly rawThreshold: number[] = [];
  /** 중앙값(확정분) */
  private readonly med: number[] = [];
  /** 이동 평균까지 거친 값(확정분)과 그 시각 */
  readonly t: number[] = [];
  readonly h: number[] = [];
  private readonly zig = newZigzag();
  private closed = false;
  private readonly medianHalf: number;
  private readonly meanHalf: number;
  /** 끝의 잠정분(tail)을 센 때의 점 수 — 점이 더 오기 전까지 다시 세지 않는다 */
  private tailCache: { count: number; t: number[]; h: number[] } | null = null;

  constructor(private readonly f: RunFilter) {
    this.medianHalf = Math.max(0, Math.floor((f.elevationMedianPoints - 1) / 2));
    this.meanHalf = Math.max(0, Math.floor((f.elevationSmoothingPoints - 1) / 2));
  }

  get count(): number {
    return this.rawH.length;
  }

  /** 이 조각의 첫 고도의 시각(= series().t[0]). count가 0이면 쓰지 않는다 */
  get firstT(): number {
    return this.rawT[0];
  }

  /** 고도 하나(m)와 그 고도 오차(m, 모르면 null). */
  add(t: number, h: number, vacc: number | null): void {
    if (this.closed) return;
    const f = this.f;
    const sigma = (vacc ?? f.defaultVerticalAccuracyM) * f.accuracyToSigma;
    this.rawT.push(t);
    this.rawH.push(h);
    this.rawThreshold.push(Math.max(f.elevationHysteresisM, f.elevationVerticalFactor * sigma));
    // 앞뒤 창이 다 찬 값만 확정한다
    while (this.med.length + this.medianHalf < this.rawH.length) this.med.push(this.medianAt(this.med.length));
    while (this.h.length + this.meanHalf < this.med.length) this.confirm(this.h.length, this.meanAt(this.med, this.h.length));
  }

  /** 더 올 점이 없다: 끝의 값을 잘린 창으로 확정한다. */
  close(): void {
    if (this.closed) return;
    while (this.med.length < this.rawH.length) this.med.push(this.medianAt(this.med.length));
    while (this.h.length < this.med.length) this.confirm(this.h.length, this.meanAt(this.med, this.h.length));
    this.closed = true;
  }

  /** 지금까지의 오르막(m). 끝의 아직 확정 안 된 값은 잘린 창으로 잠정 계산한다. */
  gain(): number {
    if (this.closed || this.h.length === this.rawH.length) return zigzagTotal(this.zig);
    const tail = this.tail();
    const z = { ...this.zig };
    for (let i = 0; i < tail.h.length; i += 1) zigzagFeed(z, tail.h[i], this.rawThreshold[this.h.length + i]);
    return zigzagTotal(z);
  }

  /** 확정된 값 + 잠정인 끝의 값(배열을 새로 만든다 — 기록 중 점마다 부르는 곳은 아래 altitudeAt 등을 쓴다). */
  series(): ElevationSeries {
    if (this.closed || this.h.length === this.rawH.length) return { t: this.t, h: this.h };
    const tail = this.tail();
    return { t: this.t.concat(tail.t), h: this.h.concat(tail.h) };
  }

  /** altitudeAtTime(this.series(), t)와 같은 값. 배열을 잇지 않고 확정분 → 잠정분 차례로 본다. */
  altitudeAt(t: number): number | null {
    const n = this.rawH.length;
    if (n === 0) return null;
    const confirmed = this.h.length;
    const tail = this.closed || confirmed === n ? null : this.tail();
    const tAt = (i: number) => (i < confirmed ? this.t[i] : tail!.t[i - confirmed]);
    const hAt = (i: number) => (i < confirmed ? this.h[i] : tail!.h[i - confirmed]);
    if (t <= tAt(0)) return hAt(0);
    if (t >= tAt(n - 1)) return hAt(n - 1);
    let lo = 0;
    let hi = n - 1;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1; // eslint-disable-line no-bitwise
      if (tAt(mid) <= t) lo = mid;
      else hi = mid;
    }
    const span = tAt(hi) - tAt(lo);
    return hAt(lo) + (hAt(hi) - hAt(lo)) * (span > 0 ? (t - tAt(lo)) / span : 0);
  }

  /** series().h의 처음 값(고도가 없으면 null) */
  firstAltitude(): number | null {
    if (this.rawH.length === 0) return null;
    if (this.h.length > 0) return this.h[0];
    return this.tail().h[0] ?? null;
  }

  /** series().h의 마지막 값(고도가 없으면 null) */
  lastAltitude(): number | null {
    if (this.rawH.length === 0) return null;
    if (this.closed || this.h.length === this.rawH.length) return this.h[this.h.length - 1] ?? null;
    const tail = this.tail();
    return tail.h[tail.h.length - 1] ?? null;
  }

  // ── 안쪽

  private confirm(index: number, value: number): void {
    this.t.push(this.rawT[index]);
    this.h.push(value);
    zigzagFeed(this.zig, value, this.rawThreshold[index]);
  }

  private medianAt(index: number): number {
    const from = Math.max(0, index - this.medianHalf);
    const to = Math.min(this.rawH.length - 1, index + this.medianHalf);
    return median(this.rawH.slice(from, to + 1));
  }

  private meanAt(values: ReadonlyArray<number>, index: number): number {
    const from = Math.max(0, index - this.meanHalf);
    const to = Math.min(values.length - 1, index + this.meanHalf);
    let sum = 0;
    for (let i = from; i <= to; i += 1) sum += values[i];
    return sum / (to - from + 1);
  }

  /**
   * 아직 확정하지 않은 끝의 값(잘린 창). 확정된 중앙값(med)은 복사하지 않고 끝의 아직 안 센 중앙값만 따로 세어 이어 본다(더하는 차례가
   * 같아 값도 같다). 점이 더 오면(add) 다시 센다 — 그 사이 snapshot이 여러 번 물어도 한 번만.
   */
  private tail(): { t: number[]; h: number[] } {
    const count = this.rawH.length;
    if (this.tailCache && this.tailCache.count === count) return this.tailCache;
    const known = this.med.length;
    const rest: number[] = [];
    for (let i = known; i < count; i += 1) rest.push(this.medianAt(i));
    const medAt = (i: number) => (i < known ? this.med[i] : rest[i - known]);
    const t: number[] = [];
    const h: number[] = [];
    for (let i = this.h.length; i < count; i += 1) {
      t.push(this.rawT[i]);
      // meanAt(중앙값 전체, i)와 같은 창·같은 차례
      const from = Math.max(0, i - this.meanHalf);
      const to = Math.min(count - 1, i + this.meanHalf);
      let sum = 0;
      for (let j = from; j <= to; j += 1) sum += medAt(j);
      h.push(sum / (to - from + 1));
    }
    this.tailCache = { count, t, h };
    return this.tailCache;
  }
}

/** 시각 t의 고도(앞뒤 값 사이를 시각으로 보간). 범위 밖이면 끝 값. */
export function altitudeAtTime(series: ElevationSeries, t: number): number | null {
  const n = series.t.length;
  if (n === 0) return null;
  if (t <= series.t[0]) return series.h[0];
  if (t >= series.t[n - 1]) return series.h[n - 1];
  let lo = 0;
  let hi = n - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1; // eslint-disable-line no-bitwise
    if (series.t[mid] <= t) lo = mid;
    else hi = mid;
  }
  const span = series.t[hi] - series.t[lo];
  return series.h[lo] + (series.h[hi] - series.h[lo]) * (span > 0 ? (t - series.t[lo]) / span : 0);
}
