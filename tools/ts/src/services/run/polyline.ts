// 경로 문자열(routePolyline)과 점 줄이기.
// 형식(docs/running-impl-notes.md 1절): 구간(일시정지로 끊긴 조각)마다 Google encoded polyline(정밀도 5)을 만들고
// 공백 한 칸으로 이어 붙인다. 구간마다 따로 인코딩하므로 각 구간은 (0,0)부터 차이를 센다. 인코딩 글자(63~126)에는
// 공백이 없어 공백이 구분자로 안전하다. 서버는 내용을 해석하지 않는다.
// 비트 연산 대신 산술로 계산한다(정밀도를 올려도 32비트에서 넘치지 않게).

import { localProjection, type LatLng } from './geo';

/** 저장·공유용 경로 줄이기 기준(m). runTrack의 RUN_FILTER.simplifyToleranceM과 같은 값. */
export const DEFAULT_SIMPLIFY_TOLERANCE_M = 2;

// Google 구현(파이썬 2 round)과 같게 .5는 0에서 먼 쪽으로 반올림한다. JS Math.round(-2.5)는 -2라 음수에서 다르다.
function roundHalfAway(value: number): number {
  return value < 0 ? -Math.floor(-value + 0.5) : Math.floor(value + 0.5);
}

function pushValue(codes: number[], delta: number): void {
  let n = delta < 0 ? -2 * delta - 1 : 2 * delta;
  while (n >= 32) {
    codes.push(32 + (n % 32) + 63);
    n = Math.floor(n / 32);
  }
  codes.push(n + 63);
}

function codesToString(codes: number[]): string {
  // 한 번에 넘기는 인자 수가 너무 많으면 엔진이 거절하므로 나눠서 만든다.
  let out = '';
  for (let i = 0; i < codes.length; i += 4096) {
    out += String.fromCharCode.apply(null, codes.slice(i, i + 4096));
  }
  return out;
}

export function encodePolyline(points: ReadonlyArray<LatLng>, precision: number = 5): string {
  const factor = 10 ** precision;
  const codes: number[] = [];
  let prevLat = 0;
  let prevLng = 0;
  for (const point of points) {
    // 차이는 반올림한 값끼리 센다(반올림 오차가 점마다 쌓이지 않게).
    const lat = roundHalfAway(point.lat * factor);
    const lng = roundHalfAway(point.lng * factor);
    pushValue(codes, lat - prevLat);
    pushValue(codes, lng - prevLng);
    prevLat = lat;
    prevLng = lng;
  }
  return codesToString(codes);
}

/** 잘못된(잘린·범위 밖 글자) 문자열이면 그 앞까지 읽은 점만 돌려준다 — 목록 썸네일이 문자열 하나로 죽지 않게. */
export function decodePolyline(encoded: string, precision: number = 5): LatLng[] {
  const factor = 10 ** precision;
  const out: LatLng[] = [];
  const length = encoded.length;
  let index = 0;
  let lat = 0;
  let lng = 0;

  const readValue = (): number | null => {
    let result = 0;
    let multiplier = 1;
    let chunk: number;
    do {
      if (index >= length) return null;
      chunk = encoded.charCodeAt(index) - 63;
      index += 1;
      if (chunk < 0 || chunk > 63 || multiplier > 2 ** 45) return null;
      result += (chunk % 32) * multiplier;
      multiplier *= 32;
    } while (chunk >= 32);
    return result % 2 === 1 ? -(result + 1) / 2 : result / 2;
  };

  while (index < length) {
    const dLat = readValue();
    if (dLat === null) break;
    const dLng = readValue();
    if (dLng === null) break;
    lat += dLat;
    lng += dLng;
    out.push({ lat: lat / factor, lng: lng / factor });
  }
  return out;
}

/** 빈 구간은 건너뛴다(그릴 것도 셀 거리도 없다). */
export function encodeSegments(segments: ReadonlyArray<ReadonlyArray<LatLng>>, precision: number = 5): string {
  const parts: string[] = [];
  for (const segment of segments) {
    if (segment.length > 0) parts.push(encodePolyline(segment, precision));
  }
  return parts.join(' ');
}

export function decodeSegments(encoded: string | null | undefined, precision: number = 5): LatLng[][] {
  if (!encoded) return [];
  const segments: LatLng[][] = [];
  for (const part of encoded.split(' ')) {
    if (!part) continue;
    const points = decodePolyline(part, precision);
    if (points.length > 0) segments.push(points);
  }
  return segments;
}

/**
 * 그릴 경로: 점 하나뿐인 조각(끊긴 뒤 한 번 잡힌 위치 — 선이 아니라 동떨어진 점으로 보였다)은 뺀다.
 * 모든 조각이 점 하나면(아주 짧은 기록) 그대로 둔다.
 */
export function drawableSegments(segments: LatLng[][]): LatLng[][] {
  const lines = segments.filter(segment => segment.length > 1);
  return lines.length > 0 ? lines : segments.filter(segment => segment.length > 0);
}

/**
 * Douglas–Peucker(허용 오차 m). 처음·끝 점은 늘 남기고, 입력 점 객체를 그대로 돌려준다(시각·고도 등 다른 값도 남음).
 * 점과 '선분' 사이 거리로 재서 출발점으로 돌아오는 경로(처음 = 끝)도 다룬다. 깊은 재귀 대신 스택으로 돈다(10만 점).
 */
export function simplify<T extends LatLng>(points: ReadonlyArray<T>, toleranceM: number = DEFAULT_SIMPLIFY_TOLERANCE_M): T[] {
  const n = points.length;
  if (n <= 2 || !(toleranceM > 0)) return points.slice();

  let minLat = Infinity;
  let maxLat = -Infinity;
  for (const point of points) {
    if (point.lat < minLat) minLat = point.lat;
    if (point.lat > maxLat) maxLat = point.lat;
  }
  const projection = localProjection(points[0], (minLat + maxLat) / 2);
  const xs = new Float64Array(n);
  const ys = new Float64Array(n);
  for (let i = 0; i < n; i += 1) {
    const xy = projection.toXY(points[i]);
    xs[i] = xy.x;
    ys[i] = xy.y;
  }

  const keep = new Uint8Array(n);
  keep[0] = 1;
  keep[n - 1] = 1;
  const tolerance2 = toleranceM * toleranceM;
  const stack: number[] = [0, n - 1];
  while (stack.length > 0) {
    const last = stack.pop() as number;
    const first = stack.pop() as number;
    if (last - first < 2) continue;
    const ax = xs[first];
    const ay = ys[first];
    const dx = xs[last] - ax;
    const dy = ys[last] - ay;
    const length2 = dx * dx + dy * dy;
    let farthest = -1;
    let farthest2 = tolerance2;
    for (let i = first + 1; i < last; i += 1) {
      let px = xs[i] - ax;
      let py = ys[i] - ay;
      if (length2 > 0) {
        const t = Math.max(0, Math.min(1, (px * dx + py * dy) / length2));
        px -= t * dx;
        py -= t * dy;
      }
      const d2 = px * px + py * py;
      if (d2 > farthest2) {
        farthest2 = d2;
        farthest = i;
      }
    }
    if (farthest >= 0) {
      keep[farthest] = 1;
      stack.push(first, farthest, farthest, last);
    }
  }

  const out: T[] = [];
  for (let i = 0; i < n; i += 1) {
    if (keep[i]) out.push(points[i]);
  }
  return out;
}
