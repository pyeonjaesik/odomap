// 개척 시험용 가짜 경로: 평면(동 x·북 y, m) 위 꺾은선을 일정한 속력으로 1초마다 찍는다. 도플러 속도·방향도 채운다.
import { localProjection } from '../../src/services/run/geo';
import { exploreDistanceM, type ExploreGraph } from '../../src/services/run/explore/exploreGraph';
import type { RawPoint } from '../../src/services/run/runTrack';

export const ORIGIN = { lat: 37.5283, lng: 126.9326 };
export const plane = localProjection(ORIGIN);
/** KST 2026-10-07 07:00 */
export const T0 = Date.UTC(2026, 9, 6, 22, 0);

/* eslint-disable no-bitwise */
export function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
/* eslint-enable no-bitwise */

function gaussian(random: () => number): number {
  let u = 0;
  while (u === 0) u = random();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * random());
}

export type PathOptions = {
  /** 꺾은선 꼭짓점(m) */
  via: ReadonlyArray<readonly [number, number]>;
  /** 속력(m/s) */
  speed: number;
  t0?: number;
  acc?: number;
  /** 위치 흔들림 표준편차(m, 천천히 흔들림) */
  noiseM?: number;
  seed?: number;
};

/** 꺾은선을 따라 1초마다 점. 마지막 꼭짓점에서 끝난다. */
export function pathPoints({ via, speed, t0 = T0, acc = 5, noiseM = 0, seed = 1 }: PathOptions): RawPoint[] {
  const random = rng(seed);
  const rho = 0.95;
  const step = noiseM * Math.sqrt(1 - rho * rho);
  let nx = noiseM > 0 ? gaussian(random) * noiseM : 0;
  let ny = noiseM > 0 ? gaussian(random) * noiseM : 0;
  const out: RawPoint[] = [];
  let t = t0;
  const push = (x: number, y: number, course: number) => {
    if (noiseM > 0) {
      nx = rho * nx + gaussian(random) * step;
      ny = rho * ny + gaussian(random) * step;
    }
    const { lat, lng } = plane.toLatLng({ x: x + nx, y: y + ny });
    out.push({ lat, lng, t, acc, alt: null, speed, course });
    t += 1000;
  };
  let carry = 0;
  for (let i = 1; i < via.length; i += 1) {
    const [x0, y0] = via[i - 1];
    const [x1, y1] = via[i];
    const length = Math.hypot(x1 - x0, y1 - y0);
    const course = ((Math.atan2(x1 - x0, y1 - y0) * 180) / Math.PI + 360) % 360;
    let d = carry;
    if (i === 1) push(x0, y0, course);
    while (d + speed <= length + 1e-9) {
      d += speed;
      push(x0 + ((x1 - x0) * d) / length, y0 + ((y1 - y0) * d) / length, course);
    }
    carry = d - length;
  }
  return out;
}

/** 점들의 시각을 t0부터 다시 매긴다(이어 붙일 때). */
export function shiftTo(points: RawPoint[], t0: number): RawPoint[] {
  if (points.length === 0) return points;
  const d = t0 - points[0].t;
  return points.map(point => ({ ...point, t: point.t + d }));
}

/** 그물 이음 길이의 합(m) — 같은 길이 두 줄이면 두 배가 된다 */
export function graphLengthM(graph: ExploreGraph): number {
  let sum = 0;
  for (const edge of graph.edges()) sum += exploreDistanceM({ lat: edge.aLat, lng: edge.aLng }, { lat: edge.bLat, lng: edge.bLng });
  return sum;
}

/** 마디를 같이 쓰지 않는데 8m 안에서 옆으로 2m 넘게 비켜 나란히 달리는 이음 쌍의 수(같은 길이 두 줄로 남은 곳 — 한 줄의 앞뒤 이음은 세지 않는다) */
export function parallelDuplicates(graph: ExploreGraph): number {
  const list = [...graph.edges()].map(edge => {
    const a = plane.toXY({ lat: edge.aLat, lng: edge.aLng });
    const b = plane.toXY({ lat: edge.bLat, lng: edge.bLng });
    const len = Math.hypot(b.x - a.x, b.y - a.y) || 1;
    return { a: edge.a, b: edge.b, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2, ux: (b.x - a.x) / len, uy: (b.y - a.y) / len };
  });
  let count = 0;
  for (let i = 0; i < list.length; i += 1) {
    for (let j = i + 1; j < list.length; j += 1) {
      const p = list[i];
      const q = list[j];
      if (p.a === q.a || p.a === q.b || p.b === q.a || p.b === q.b) continue;
      if (Math.hypot(p.mx - q.mx, p.my - q.my) > 8) continue;
      const aside = Math.abs((q.mx - p.mx) * p.uy - (q.my - p.my) * p.ux);
      if (aside >= 2 && Math.abs(p.ux * q.ux + p.uy * q.uy) > 0.9) count += 1;
    }
  }
  return count;
}
