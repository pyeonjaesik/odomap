// 개척 그물을 지도 조각(웹 메르카토르 z14, 서울에서 약 2km × 2km)으로 나눠 저장·전송·그리기 — 순수 계산. 앱과 서버가 같은 파일을 쓴다:
//   앱  src/services/run/explore/exploreTile.ts
//   서버 src/services/workouts/track/explore/exploreTile.ts
// 두 파일은 글자 하나까지 같아야 한다(scripts/check-shared-run-code.sh).
//
// 마디는 위치가 든 조각에, 이음은 가운데점이 든 조각에 둔다. 이음에는 양 끝 위치가 같이 있어 조각 하나만으로 그릴 수 있다.
// 새 기록을 넣을 때는 그 길 둘레(EXPLORE_TILE_MARGIN_M)의 조각을 모두 읽는다 — 붙을 자리(붙는 거리 + 띠 + 띠 밖 한 칸, 54m까지 — 이음 반 6m를
// 더해 마디 60m)와 그 자리까지 그물을 따라가는 길(routeMaxM 80m — 길 위 마디는 양 끝 중 한쪽에서 40m 안)이 모두 읽은 조각 안에 있게(v4, 예전 60m.
// 다듬은 위치가 원본 점에서 몇 m 비켜도 남게 120m).
// 위도·경도는 1e-6도 정수로 담는다(exploreGraph가 마디를 만들 때 같은 자리에서 반올림한다).
// 그리기용 선(exploreLines)은 smooth면 꼭짓점을 고르게 다듬는다(smoothExploreCoords — 그리는 쪽만, 저장한 그물은 그대로).

import { ExploreGraph, exploreDistanceM, type ExploreEdge } from './exploreGraph';

export const EXPLORE_TILE_Z = 14;
/** 멀리서 볼 때 서버가 묶어 주는 조각의 확대 단계(z11 = z14 조각 8 × 8) */
export const EXPLORE_OVERVIEW_Z = 11;
/** 새 기록의 길에서 이만큼(m) 안의 조각을 읽는다 */
export const EXPLORE_TILE_MARGIN_M = 120;
/** 그리기용 선 다듬기 횟수([1, 2, 1]/4 — 두 번이면 마디마다 엇갈린 지그재그는 사라지고 길모퉁이는 3~4m 둥글어진다) */
export const EXPLORE_LINE_SMOOTH_PASSES = 2;

export type ExploreTileXY = { x: number; y: number };

/** 조각 하나의 내용(저장·전송 모양 같음). */
export type ExploreTileData = {
  /** 마디 [번호, 위도e6, 경도e6, 지나간 기록 수, 처음 생긴 날] */
  n: Array<[number, number, number, number, number]>;
  /** 이음 [작은 번호, 큰 번호, 지나간 기록 수, 처음 생긴 날, a 위도e6, a 경도e6, b 위도e6, b 경도e6] */
  e: Array<[number, number, number, number, number, number, number, number]>;
};

/**
 * 그리기용 선 하나: 같은 굵기 단계로 이어진 이음들. coords는 [위도, 경도]. nodes·stops(exploreLines의 nodes 옵션)는 그 선이 지나는 마디 번호(선의
 * 차례)와 마디마다 선의 처음부터 잰 길이의 몫(0~1 — 줄이기 전 선, 다듬었으면 다듬은 선으로 잰다) — 걸은 차례를 선 위의 움직임으로 옮길 때
 * (exploreLineMoves).
 */
export type ExploreLine = { level: number; coords: Array<[number, number]>; nodes?: number[]; stops?: number[] };

/**
 * 선 위의 움직임 하나(exploreLineMoves): [선 번호 — 세지 않은 걸음(지나간 수를 올리지 않음)이면 -1 - 번호, 처음 몫, 끝 몫](선의 처음부터 잰 길이의
 * 몫 0~1 — 끝이 처음보다 작으면 거꾸로 지난다). null은 붓을 든다(다음 움직임은 건너뛴 자리에서 시작).
 */
export type ExploreLineMove = [number, number, number] | null;

const RAD = Math.PI / 180;
const METERS_PER_DEGREE = (6371008.8 * Math.PI) / 180;
const MAX_MERCATOR_LAT = 85.0511287798;

export function exploreTileOf(lat: number, lng: number, z: number = EXPLORE_TILE_Z): ExploreTileXY {
  const n = 2 ** z;
  const clampedLat = Math.max(-MAX_MERCATOR_LAT, Math.min(MAX_MERCATOR_LAT, lat));
  const latRad = clampedLat * RAD;
  const x = Math.floor(((lng + 180) / 360) * n);
  const y = Math.floor(((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) * n);
  return { x: Math.max(0, Math.min(n - 1, x)), y: Math.max(0, Math.min(n - 1, y)) };
}

/** 조각의 위경도 범위. */
export function exploreTileBounds(tile: ExploreTileXY, z: number = EXPLORE_TILE_Z): {
  minLat: number;
  minLng: number;
  maxLat: number;
  maxLng: number;
} {
  const n = 2 ** z;
  const lngOf = (x: number) => (x / n) * 360 - 180;
  const latOf = (y: number) => Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / n))) / RAD;
  return { minLng: lngOf(tile.x), maxLng: lngOf(tile.x + 1), maxLat: latOf(tile.y), minLat: latOf(tile.y + 1) };
}

export function exploreTileKey(tile: ExploreTileXY): string {
  return `${tile.x}/${tile.y}`;
}

/** 위경도 범위를 덮는 조각들(z 단계). */
export function exploreTilesInBounds(
  bounds: { minLat: number; minLng: number; maxLat: number; maxLng: number },
  z: number = EXPLORE_TILE_Z,
): ExploreTileXY[] {
  const a = exploreTileOf(bounds.maxLat, bounds.minLng, z);
  const b = exploreTileOf(bounds.minLat, bounds.maxLng, z);
  const out: ExploreTileXY[] = [];
  for (let x = a.x; x <= b.x; x += 1) {
    for (let y = a.y; y <= b.y; y += 1) out.push({ x, y });
  }
  return out;
}

/** 점들에서 marginM 안을 덮는 z14 조각들(겹치지 않게, x·y 순). */
export function exploreTilesAround(
  points: ReadonlyArray<{ lat: number; lng: number }>,
  marginM: number = EXPLORE_TILE_MARGIN_M,
): ExploreTileXY[] {
  const seen = new Map<string, ExploreTileXY>();
  for (const point of points) {
    if (!Number.isFinite(point.lat) || !Number.isFinite(point.lng)) continue;
    const dLat = marginM / METERS_PER_DEGREE;
    const dLng = marginM / Math.max(1, METERS_PER_DEGREE * Math.cos(point.lat * RAD));
    for (const tile of exploreTilesInBounds({
      minLat: point.lat - dLat,
      maxLat: point.lat + dLat,
      minLng: point.lng - dLng,
      maxLng: point.lng + dLng,
    })) {
      const key = exploreTileKey(tile);
      if (!seen.has(key)) seen.set(key, tile);
    }
  }
  return [...seen.values()].sort((p, q) => p.x - q.x || p.y - q.y);
}

function e6(value: number): number {
  return Math.round(value * 1e6);
}

/** 조각 내용을 그물에 채운다. */
export function loadExploreTile(graph: ExploreGraph, data: ExploreTileData): void {
  for (const [id, lat, lng, visits, day] of data.n) graph.loadNode({ id, lat: lat / 1e6, lng: lng / 1e6, visits, day });
  for (const [a, b, visits, day, aLat, aLng, bLat, bLng] of data.e) {
    graph.loadEdge({ a, b, visits, day, aLat: aLat / 1e6, aLng: aLng / 1e6, bLat: bLat / 1e6, bLng: bLng / 1e6 });
  }
}

/** 그물을 조각별로 나눈다(조각 열쇠 → 내용). only가 있으면 그 조각만. 마디·이음은 번호 순. */
export function exploreTilesOfGraph(graph: ExploreGraph, only?: ReadonlySet<string>): Map<string, ExploreTileData> {
  const out = new Map<string, ExploreTileData>();
  const slot = (key: string): ExploreTileData => {
    let data = out.get(key);
    if (!data) {
      data = { n: [], e: [] };
      out.set(key, data);
    }
    return data;
  };
  for (const node of graph.nodes()) {
    const key = exploreTileKey(exploreTileOf(node.lat, node.lng));
    if (only && !only.has(key)) continue;
    slot(key).n.push([node.id, e6(node.lat), e6(node.lng), node.visits, node.day]);
  }
  for (const edge of graph.edges()) {
    const key = exploreTileKey(exploreTileOf((edge.aLat + edge.bLat) / 2, (edge.aLng + edge.bLng) / 2));
    if (only && !only.has(key)) continue;
    slot(key).e.push([edge.a, edge.b, edge.visits, edge.day, e6(edge.aLat), e6(edge.aLng), e6(edge.bLat), e6(edge.bLng)]);
  }
  for (const data of out.values()) {
    data.n.sort((p, q) => p[0] - q[0]);
    data.e.sort((p, q) => p[0] - q[0] || p[1] - q[1]);
  }
  return out;
}

/** 지나간 기록 수 → 굵기 단계(1~5). 자주 다닌 길일수록 굵고 진하게 그린다. */
export function exploreLineLevel(visits: number): number {
  if (visits >= 9) return 5;
  if (visits >= 5) return 4;
  if (visits >= 3) return 3;
  if (visits >= 2) return 2;
  return 1;
}

/**
 * 그리기용 선을 다듬는다(2026-10-09 — 위치가 흔들린 길은 8m 마디마다 몇 m씩 엇갈려 선이 지그재그였다): 안쪽 꼭짓점을 이웃과 [1, 2, 1]/4로
 * passes번 고르게 한다. 양 끝은 그대로라 갈림길·조각 경계·굵기가 바뀌는 자리에서 다른 선과 이어진다. 저장한 그물(마디·이음)은 바꾸지 않는다 —
 * 지도·다시 보기·카드·워치가 그릴 때만. 1e-6도로 반올림.
 */
export function smoothExploreCoords(coords: ReadonlyArray<readonly [number, number]>, passes: number = EXPLORE_LINE_SMOOTH_PASSES): Array<[number, number]> {
  const n = coords.length;
  if (n <= 2 || !(passes > 0)) return coords.map(([lat, lng]) => [lat, lng] as [number, number]);
  let lat = coords.map(point => point[0]);
  let lng = coords.map(point => point[1]);
  for (let pass = 0; pass < passes; pass += 1) {
    const nextLat = lat.slice();
    const nextLng = lng.slice();
    for (let i = 1; i < n - 1; i += 1) {
      nextLat[i] = (lat[i - 1] + 2 * lat[i] + lat[i + 1]) / 4;
      nextLng[i] = (lng[i - 1] + 2 * lng[i] + lng[i + 1]) / 4;
    }
    lat = nextLat;
    lng = nextLng;
  }
  const round6 = (value: number) => Math.round(value * 1e6) / 1e6;
  return lat.map((value, i) => [i === 0 || i === n - 1 ? value : round6(value), i === 0 || i === n - 1 ? lng[i] : round6(lng[i])] as [number, number]);
}

function simplifyCoords(coords: Array<[number, number]>, toleranceM: number): Array<[number, number]> {
  if (!(toleranceM > 0) || coords.length <= 2) return coords;
  const keep = new Uint8Array(coords.length);
  keep[0] = 1;
  keep[coords.length - 1] = 1;
  const stack: Array<[number, number]> = [[0, coords.length - 1]];
  while (stack.length > 0) {
    const [from, to] = stack.pop() as [number, number];
    const a = coords[from];
    const b = coords[to];
    const kx = METERS_PER_DEGREE * Math.cos(((a[0] + b[0]) / 2) * RAD);
    const bx = (b[1] - a[1]) * kx;
    const by = (b[0] - a[0]) * METERS_PER_DEGREE;
    const len2 = bx * bx + by * by;
    let worst = -1;
    let worstD = toleranceM;
    for (let i = from + 1; i < to; i += 1) {
      const px = (coords[i][1] - a[1]) * kx;
      const py = (coords[i][0] - a[0]) * METERS_PER_DEGREE;
      let d: number;
      if (len2 === 0) {
        d = Math.sqrt(px * px + py * py);
      } else {
        const f = Math.max(0, Math.min(1, (px * bx + py * by) / len2));
        d = Math.hypot(px - f * bx, py - f * by);
      }
      if (d > worstD) {
        worst = i;
        worstD = d;
      }
    }
    if (worst >= 0) {
      keep[worst] = 1;
      stack.push([from, worst], [worst, to]);
    }
  }
  return coords.filter((_, i) => keep[i] === 1);
}

/** 선(꼭짓점들)의 처음부터 잰 길이의 몫(0~1, 소수 넷째 자리 — 2km 선에서 20cm. 응답이 작게 — 산책마다 조각 위 움직임의 두 수) */
function lineStops(coords: ReadonlyArray<readonly [number, number]>): number[] {
  const cum = [0];
  for (let i = 1; i < coords.length; i += 1) {
    cum.push(cum[i - 1] + exploreDistanceM({ lat: coords[i - 1][0], lng: coords[i - 1][1] }, { lat: coords[i][0], lng: coords[i][1] }));
  }
  const total = cum[cum.length - 1];
  return cum.map((value, i) => (i === cum.length - 1 && total > 0 ? 1 : total > 0 ? Math.round((value / total) * 1e4) / 1e4 : 0));
}

/**
 * 이음들을 그리기 좋은 선으로 묶는다: 굵기 단계가 같은 이음끼리 갈림길(이웃이 둘이 아닌 마디)에서 갈림길까지 한 선으로.
 * smooth면 선을 다듬고(smoothExploreCoords — 그리기용), simplifyM > 0이면 그만큼(m) 줄인다(멀리서 볼 때). 짧은 선(minLengthM 미만, 홀로
 * 떨어진 것)은 뺀다. nodes면 선마다 지나는 마디와 그 자리(ExploreLine nodes·stops)를 같이 낸다.
 */
export function exploreLines(
  edges: Iterable<Pick<ExploreEdge, 'a' | 'b' | 'visits' | 'aLat' | 'aLng' | 'bLat' | 'bLng'>>,
  options: { simplifyM?: number; minLengthM?: number; smooth?: boolean; nodes?: boolean } = {},
): ExploreLine[] {
  type Half = { to: number; key: number };
  const byLevel = new Map<number, { adjacency: Map<number, Half[]>; coord: Map<number, [number, number]>; edges: number }>();
  let key = 0;
  for (const edge of edges) {
    if (edge.a === edge.b) continue;
    const level = exploreLineLevel(edge.visits);
    let group = byLevel.get(level);
    if (!group) {
      group = { adjacency: new Map(), coord: new Map(), edges: 0 };
      byLevel.set(level, group);
    }
    group.coord.set(edge.a, [edge.aLat, edge.aLng]);
    group.coord.set(edge.b, [edge.bLat, edge.bLng]);
    const k = key;
    key += 1;
    group.edges += 1;
    const la = group.adjacency.get(edge.a);
    if (la) la.push({ to: edge.b, key: k });
    else group.adjacency.set(edge.a, [{ to: edge.b, key: k }]);
    const lb = group.adjacency.get(edge.b);
    if (lb) lb.push({ to: edge.a, key: k });
    else group.adjacency.set(edge.b, [{ to: edge.a, key: k }]);
  }
  const out: ExploreLine[] = [];
  const levels = [...byLevel.keys()].sort((p, q) => p - q);
  for (const level of levels) {
    const group = byLevel.get(level) as { adjacency: Map<number, Half[]>; coord: Map<number, [number, number]>; edges: number };
    const used = new Set<number>();
    const walkFrom = (start: number, first: Half): { coords: Array<[number, number]>; ids: number[] } => {
      const coords: Array<[number, number]> = [group.coord.get(start) as [number, number]];
      const ids = [start];
      let at = start;
      let half: Half | undefined = first;
      while (half !== undefined && !used.has(half.key)) {
        used.add(half.key);
        at = half.to;
        coords.push(group.coord.get(at) as [number, number]);
        ids.push(at);
        const next: Half[] = group.adjacency.get(at) ?? [];
        if (next.length !== 2) break;
        half = next.find(h => !used.has(h.key));
      }
      return { coords, ids };
    };
    const ids = [...group.adjacency.keys()].sort((p, q) => p - q);
    // 갈림길·끝에서 시작하는 선 먼저, 남은 것은 고리
    for (const pass of [0, 1]) {
      for (const id of ids) {
        const halves = group.adjacency.get(id) as Half[];
        if (pass === 0 && halves.length === 2) continue;
        for (const half of halves) {
          if (used.has(half.key)) continue;
          const { coords, ids: nodeIds } = walkFrom(id, half);
          if (coords.length < 2) continue;
          let length = 0;
          for (let i = 1; i < coords.length; i += 1) {
            length += exploreDistanceM({ lat: coords[i - 1][0], lng: coords[i - 1][1] }, { lat: coords[i][0], lng: coords[i][1] });
          }
          if (length < (options.minLengthM ?? 0)) continue;
          const drawn = options.smooth ? smoothExploreCoords(coords) : coords;
          const line: ExploreLine = { level, coords: simplifyCoords(drawn, options.simplifyM ?? 0) };
          if (options.nodes) {
            line.nodes = nodeIds;
            line.stops = lineStops(drawn);
          }
          out.push(line);
        }
      }
    }
  }
  return out;
}

/** 이음 찾기 열쇠(작은 번호 × 2^26 + 큰 번호 — exploreGraph의 이음 열쇠와 같은 셈) */
function pairKey(a: number, b: number): number {
  return a < b ? a * 67108864 + b : b * 67108864 + a;
}

/**
 * 걸은 차례(ExploreWalkResult.walkPath — 마디를 옮겨 간 차례) → 선(nodes·stops가 있는 exploreLines의 선) 위의 움직임(ExploreLineMove)(2026-10-10 —
 * 다시 보기·산책 카드의 붓이 걸은 그대로 오간다). 같은 선을 같은 쪽으로 이어 가는 걸음은 한 움직임으로 묶는다(센 걸음·세지 않은 걸음은 따로).
 * 선에 없는 이음(그 응답에서 빠진 길)을 지나면 붓을 들고 다음 자리로 건너뛴다. 새 획(0 — 일시정지·끊김·탈것 뒤)도 붓을 든다(같은 마디에서
 * 다시 시작하면 그대로 잇는다).
 */
export function exploreLineMoves(lines: ReadonlyArray<Pick<ExploreLine, 'nodes' | 'stops'>>, path: ReadonlyArray<number>): ExploreLineMove[] {
  // 이음 → [선 번호, 그 선에서 앞 마디의 자리]
  const where = new Map<number, [number, number]>();
  lines.forEach((line, index) => {
    const { nodes, stops } = line;
    if (!nodes || !stops || nodes.length !== stops.length) return;
    for (let i = 1; i < nodes.length; i += 1) {
      const key = pairKey(nodes[i - 1], nodes[i]);
      if (!where.has(key)) where.set(key, [index, i - 1]);
    }
  });
  const out: ExploreLineMove[] = [];
  let at: number | null = null;
  let lifted = false;
  let last: [number, number, number] | null = null;
  for (let k = 0; k < path.length; k += 1) {
    const token = path[k];
    if (token === 0) {
      const start = k + 1 < path.length ? path[k + 1] : null;
      k += 1;
      if (start === null || start !== at) lifted = true;
      at = start;
      continue;
    }
    const to = Math.abs(token);
    const found = at === null ? undefined : where.get(pairKey(at, to));
    at = to;
    if (!found) {
      lifted = true;
      continue;
    }
    const [index, i] = found;
    const { nodes, stops } = lines[index] as Required<Pick<ExploreLine, 'nodes' | 'stops'>>;
    const forward = nodes[i + 1] === to;
    const from = forward ? stops[i] : stops[i + 1];
    const end = forward ? stops[i + 1] : stops[i];
    const id = token > 0 ? index : -1 - index;
    if (lifted) {
      if (out.length > 0) out.push(null);
      last = null;
      lifted = false;
    }
    if (last !== null && last[0] === id && last[2] === from && (last[2] > last[1]) === (end > from)) {
      last[2] = end;
    } else {
      last = [id, from, end];
      out.push(last);
    }
  }
  return out;
}
