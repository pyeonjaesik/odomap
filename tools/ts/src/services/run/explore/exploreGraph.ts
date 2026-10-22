// 개척(걸은 길 지도) 계산 — 순수 계산(React·네이티브·네트워크 없음). 앱과 서버가 같은 파일을 쓴다:
//   앱  src/services/run/explore/exploreGraph.ts             — 기록 중 '오늘 개척' 어림, 기록 직후 미리보기
//   서버 src/services/workouts/track/explore/exploreGraph.ts  — 확정(내 길 지도·개척 거리)
// 두 파일은 글자 하나까지 같아야 한다(scripts/check-shared-run-code.sh). 다른 파일을 부르지 않는다.
//
// 걸은 길은 '마디'(점)와 '이음'(마디 둘을 잇는 곧은 선, maxEdgeM 이하)으로 된 그물로 쌓는다. 새 기록의 길(걷고 뛴 구간만)을 stepM마다 다시
// 찍으며 한 점씩 본다(v4, 2026-10-09 — 사용자: "같은 거리를 자주 왔다갔다하면, 그 길은 굵어지는 거야 … 두 번 왔다갔다하면 그냥 두 줄이 생기는
// 상황이야". 예전엔 마디에만 10~16m로 붙고 이번 기록이 방금 만든 마디(길을 따라 40m 안)엔 붙지 않아, 왕복의 돌아오는 길이 몇~15m 옆으로
// 흔들리면 갈 때 선 옆에 두 번째 선이 생겼고 굵어지지도 않았다):
//  - 아는 길에 붙기: 점에서 가장 가까운 이음(선분까지 수직 거리)을 찾는다. 붙는 거리는 위치 오차에 따라 snapMinM~snapMaxM(오차가 크면 넓게).
//    붙으면 그물을 따라 그 자리까지 간다(지나간 이음을 센다 — 지름길 이음을 만들지 않는다, routeFactor·routeSlackM·routeMaxM 안).
//  - 옆으로 흔들린 띠: 붙는 거리 밖이라도 띠 안의 걸음은 바로 새 길로 만들지 않고 쥐고 있는다. 띠의 너비는 형편마다 — 아는 길 위를 가던 중이면
//    driftBandM, 이번 기록이 만든 길 위를 가던 중(돌아선 왕복 — 갈 때·올 때 위치가 따로 흔들린다)이면 ownBandM, 새 길을 걷다 아는 길과
//    나란히(parallelDeg) 들어오면 freeBandM(좁게 — 25m 옆 다른 골목이 흔들림으로 가까워져도 합치지 않게). 다시 붙거나 띠를 driftHoldM 넘게
//    따라가면 아는 길로(나란한 두 번째 선을 만들지 않는다), 띠 밖으로 exitConfirmM 넘게 잇달아 나가거나 아주 멀리 나가면 끝의 먼 걸음부터 새 길
//    (갈림길에서 비스듬히 갈라진 길 — 갈라진 자리가 뒤로 밀리지 않게, 짧은 흔들림으로 나란한 혹이 생기지 않게).
//  - 이번 기록이 만든 길: 거리가 ownRatio × (그 자리를 만든 뒤 걸은 길이) 안일 때만 붙는다 — 앞으로 걷거나 모퉁이를 도는 동안은 방금 만든 마디에
//    붙지 않고(거리 ≈ 걸은 길이), 돌아서면(왕복) 갈 때 만든 길에 붙는다(거리 ≪ 걸은 길이). 방금 만든 길 끝의 옆이나 뒤로 간 걸음(앞으로 더 간 걸음은
//    바로 새 길)은 ownHoldM까지 쥐고, 그 길과 나란히 돌아오는 걸음 uTurnPoints개의 거리 가운데값이 띠(driftBandM) 안이면 돌아선 왕복(쥔 걸음도 그
//    길로 — 길을 건너 돌아와도 끝에 가시가 없다), 아니면 좁은 블록을 돌아 옆 골목으로 온 것(새 길). 두 경우는 생김새가 같아 한 점으로는 가리지 않는다.
//  - 붙을 곳이 없으면 그 자리에 새 마디를 만들어 앞 마디와 잇는다(처음 걷는 길). 이 걸음의 거리(다시 찍은 앞 점에서 걸어 온 길이)가 '개척'이다 —
//    걸은 거리를 넘지 않는다(v2). 새 길에서 아는 길로 들어가는 걸음(이음을 새로 만듦)도 개척에 든다.
// 같은 길을 되짚어 와도(왕복) 갈 때 만든 길을 따라가 개척이 두 번 세지 않는다.
// 지나간 수(visits): 이음마다 기록 하나가 지난 수를 passCap까지 센다(v4 — 예전엔 기록 하나는 한 번). 같은 이음을 다시 지나는 것은 되짚기 시작한
// 자리에서 repassM 넘게 멀어졌을 때만 센다(돌아서 걸어간 왕복·같은 고리 두 바퀴는 세고, 서 있는 동안의 흔들림·몇 m 뒤로 섰다 다시 간 것은 세지
// 않는다). 걸음으로 걷는 것을 확인한 걸음(paced — onFoot, 서 있는 흔들림은 그물에 오기 전에 빠진다)이면 repassPacedM만 멀어져도 센다(v5,
// 2026-10-10 — 사용자: "지금 왔다갔다 왕복했는데, 그 점이 반영되지 않았어": 7m쯤 오간 산책이 16m에 못 미쳐 한 번으로 남았다). 마디는 기록마다
// 한 번. 자주 다닌 길일수록 굵고 진하게 그린다(exploreTile.exploreLineLevel). 기록 결과의 walkPasses = 이번에 두 번 넘게 지난 이음.
// 걸은 차례(walkPath, v5): 그물 위를 마디에서 마디로 옮겨 간 차례를 그대로 적는다(다시 보기의 붓이 걸은 그대로 오가며 다시 지난 자리만 굵어지게 —
// 사용자: "포인트가 왕복운동을 하면서 기존 선이 굵어지게 … 지나간 위치는 포인트가 다 표시해야해"). 세지 않은 걸음(되짚기가 모자람·passCap을 넘음)도
// 적되 표시해 둔다 — 점은 지나가도 굵어지지 않는다. 그래서 다시 보기의 끝 모습 = 지도의 지나간 수.
// 약한 위치(weak: 오차가 크거나 오래 끊긴 뒤의 위치)는 아는 길에 붙기만 하고 새 마디를 만들지 않는다(그 자리에서 길이 끊긴 것으로 본다).
// 일시정지·위치 끊김(restart) 뒤 끊기 전 마지막 마디의 붙는 거리 안에서 다시 걸으면 그 마디에서 잇는다(v2, v4는 띠 안이면 새 이음으로 잇는다).
// 줄기가 끊길 때(끊김·걷지 않은 구간) 마지막 점이 찍은 자리에서 stepM의 반 넘게 갔으면 그 점도 찍는다(끝낼 때와 같게, v3).
// 저장한 그물은 지우지도 옮기지도 않는다(마디·이음 번호를 기록 결과가 가리킨다) — 나란한 선을 나중에 합치지 않고 만들 때 막는다(띠에서 쥐고 있다가
// 정한다). 화면의 선 다듬기는 그리는 쪽(exploreTile.exploreLines smooth)만 한다.
//
// 위치는 마디를 만들 때 1e-6도(약 0.1m)로 반올림한다 — 저장한 정수와 계산에 쓴 값이 같게. 같은 그물에 같은 길을 같은 순서로 넣으면
// 늘 같은 답이다(그물을 읽은 차례와 상관없이 — 고를 때 같은 거리면 번호가 작은 쪽). 마디 번호는 그물마다 1부터 늘어난다(지우지 않는다 —
// 기록을 지우면 그물을 처음부터 다시 쌓는다).

export const EXPLORE_RULES = {
  /**
   * 계산이 바뀌면 올린다(서버가 저장한 결과를 다시 쌓을지 판단). v4(2026-10-09): 이음에 붙기·흔들린 띠·돌아선 길·기록 안에서 지난 수.
   * v5(2026-10-10): 걸음으로 확인한 짧은 왕복도 센다(repassPacedM)·걸은 차례(walkPath)
   */
  version: 5,
  /** 길을 이만큼(m)마다 다시 찍어 마디로 쓴다 */
  stepM: 8,
  /** 아는 길(이음)에 붙는 거리(m): 위치 오차 × snapAccuracyFactor를 이 사이로(오차를 모르면 snapMinM) */
  snapMinM: 12,
  snapMaxM: 24,
  snapAccuracyFactor: 1.2,
  /** 붙는 거리 밖 이만큼(m)이 '옆으로 흔들린 띠' — 그 안의 걸음은 쥐고 있다가 아는 길인지 새 길인지 정한다(아는 길 위를 가던 중일 때) */
  driftBandM: 10,
  /** 이번 기록이 만든 길 위를 가던 중(돌아선 왕복)이면 띠를 이만큼(m)까지 — 갈 때·올 때 위치가 따로 흔들린다 */
  ownBandM: 20,
  /** 새 길을 걷다가 아는 길과 나란히 들어올 때의 띠(m) — 좁게(나란한 다른 골목을 합치지 않게) */
  freeBandM: 4,
  /** 띠 안을 이만큼(m) 넘게 따라가면 아는 길로 본다(같은 길을 흔들린 위치로 걸었다) */
  driftHoldM: 64,
  /** 띠 밖(띠 끝 + driftBandM 안)으로 이만큼(m) 잇달아 나가야 새 길로 본다 — 그 전에 띠로 돌아오면 흔들림(더 멀리 나가면 바로) */
  exitConfirmM: 32,
  /** 새 길에서 띠로 들어갈 때 걷는 방향과 그 길의 각이 이 안(도)이어야 쥔다(가로지르는 길은 쥐지 않는다) */
  parallelDeg: 45,
  /** 이번 기록이 만든 길에 붙으려면 거리 ≤ 이 비율 × (그 자리를 만든 뒤 걸은 길이) */
  ownRatio: 0.5,
  /** 방금 만든 길 끝 옆의 걸음을 쥐고 있는 길이(m) — 돌아서면 그 길에 얹는다(길을 건너 돌아오는 몇 걸음까지) */
  ownHoldM: 40,
  /** 길 끝 옆을 쥐고 있다가 그 길과 나란히 돌아오는 걸음이 이만큼 모이면 거리의 가운데값으로 정한다(띠 안이면 돌아선 왕복, 아니면 나란한 다른 길) */
  uTurnPoints: 4,
  /** 새 이음은 이보다(m) 길면 사이에 마디를 넣는다(붙을 자리가 마디에서 멀지 않게) */
  maxEdgeM: 12,
  /** 붙은 자리까지 그물을 따라가는 길: 곧은 거리 × routeFactor와 곧은 거리 + routeSlackM 중 긴 쪽, routeMaxM(m)까지 */
  routeFactor: 2,
  routeSlackM: 16,
  routeMaxM: 80,
  /** 기록 하나가 한 이음을 지난 수는 이만큼까지 센다(왕복이면 2 — 자주 오가면 굵게) */
  passCap: 3,
  /** 같은 이음을 다시 지난 것은 되짚기 시작한 자리에서 이만큼(m) 멀어져야 센다(서 있는 동안의 흔들림은 세지 않는다) */
  repassM: 16,
  /**
   * 걸음으로 걷는 것을 확인한 걸음(ExplorePoint.paced)이면 이만큼(m)만 멀어져도 센다(v5) — 서 있는 흔들림은 걸음으로 이미 빠졌고(onFoot 서 있음),
   * 걷다 돌아선 몇 m 왕복도 굵어지게. 마디 사이(stepM)의 반이라 이음 하나를 되짚으면 센다
   */
  repassPacedM: 4,
  /** 앞 점과 이보다 멀면(m) 잇지 않는다(끊김) */
  linkMaxM: 60,
  /** 앞 점과 이보다 오래(초) 끊겼으면 잇지 않는다 */
  linkMaxS: 90,
} as const;

const RAD = Math.PI / 180;
/** 위도 1도의 길이(m) — geo.ts와 같은 값(지구 평균 반지름 6,371,008.8m) */
const METERS_PER_DEGREE = (6371008.8 * Math.PI) / 180;
/** 마디 찾기 칸의 크기(m) — 찾는 거리를 칸 수로 덮는다 */
const CELL_M = 25;
/** 이음 열쇠 = 작은 번호 × EDGE_KEY_BASE + 큰 번호(번호는 2^26 = 6,700만 미만) */
const EDGE_KEY_BASE = 67108864;
/** 나란한지 보는 각의 cos */
const PARALLEL_COS = Math.cos(EXPLORE_RULES.parallelDeg * RAD);

export type ExploreLatLng = { lat: number; lng: number };

export type ExploreNode = {
  id: number;
  lat: number;
  lng: number;
  /** 지나간 기록 수 */
  visits: number;
  /** 처음 생긴 날(서울 날짜 번호 — 1970-01-01이 0) */
  day: number;
};

export type ExploreEdge = {
  /** 작은 마디 번호 */
  a: number;
  /** 큰 마디 번호 */
  b: number;
  /** 지나간 수(v4 — 기록마다 passCap까지) */
  visits: number;
  day: number;
  /** 양 끝 위치(마디는 움직이지 않으므로 이음에 같이 둔다 — 조각 경계에서 끝 마디가 다른 조각에 있어도 그릴 수 있게) */
  aLat: number;
  aLng: number;
  bLat: number;
  bLng: number;
};

/** 기록의 한 점(다듬은 위치). onFoot: 걷거나 뛴 구간인지(explorePipeline이 가림). */
export type ExplorePoint = {
  /** ms epoch */
  t: number;
  lat: number;
  lng: number;
  onFoot: boolean;
  /** 약한 위치 — 새 마디를 만들지 않는다 */
  weak?: boolean;
  /** 앞 점과 잇지 않는다(일시정지·필터를 새로 시작한 자리) */
  restart?: boolean;
  /** 위치 오차(m, 기기가 준 원본 점의 것 — 모르면 null). 붙는 거리를 정한다(v4) */
  acc?: number | null;
  /** 걸음으로 걷는 것을 확인한 걸음(onFoot paced) — 짧은 왕복도 다시 지난 것으로 센다(v5, repassPacedM) */
  paced?: boolean;
};

export type ExploreWalkResult = {
  /** 이번 기록이 처음 걸은 길(m) — 새 이음을 만들며 걸은 거리(다시 찍은 점 사이)라 onFootM을 넘지 않는다.
   *  (2026-10-07 v2: 예전엔 새 이음의 길이를 더해, 위치가 흔들려 지그재그로 이어지거나 아는 마디에 붙을 때 걸은 거리보다 커졌다 — 갤럭시 720m 고리에서 700.2 > 698.2) */
  pioneeredM: number;
  /** 걷고 뛴 길(다시 찍은 점 사이의 길이 합, m) */
  onFootM: number;
  /** 이번 기록이 만든 마디 수 */
  newNodes: number;
  /** 이번 기록이 새로 만든 이음([작은 번호, 큰 번호]) — 기록 직후 '새로 걸은 길' 그림 */
  newEdges: Array<[number, number]>;
  /** 이번 기록이 지난 이음(새 이음 포함, 처음 지난 차례로) — 기록 상세 지도의 '이번 길'. 걷고 뛴 길만이라 탈것 구간은 없다(2026-10-07 —
   *  예전엔 러닝 거리 계산의 선을 그려 버스 구간이 회색으로 남았다) */
  walkEdges: Array<[number, number]>;
  /** 이번 기록이 두 번 넘게 지난 이음([작은 번호, 큰 번호, 지난 수 2~passCap], walkEdges 차례 — v4). 없는 이음은 한 번 */
  walkPasses: Array<[number, number, number]>;
  /**
   * 이번 기록이 그물 위를 걸은 차례(v5): 0 다음 수는 새 획의 첫 마디(기록의 처음·일시정지·끊김·탈것 뒤 — 붓을 든다), 그 밖의 수는 차례로 옮겨 간
   * 마디 — 양수면 그 이음의 지난 수를 올린 걸음, 음수(-마디)면 세지 않은 걸음(되짚기가 repass…M에 못 미쳤거나 passCap을 넘음 — 점은 지나가도
   * 굵어지지 않는다). 양수인 걸음을 이음마다 세면 그 이음의 지난 수(walkEdges·walkPasses)와 같다. 이음 하나에 한 칸이라 8m에 한 칸쯤
   */
  walkPath: number[];
};

/** 그물 위 붙을 자리 후보(점에서 가장 가까운 이음의 자리) */
export type ExploreNear = {
  /** 이음 양 끝(작은 번호, 큰 번호) — 이음 없이 홀로 있는 마디면 a === b */
  a: number;
  b: number;
  /** 가장 가까운 자리가 a → b 사이 어디인지(0~1) */
  t: number;
  /** 점에서 그 자리까지(m) */
  d: number;
  /** 점이 막다른 끝 마디 너머, 길을 곧게 이은 쪽(45° 안)에 있다 — 길 끝에서 앞으로 더 간 걸음(옆으로 비킨 걸음은 아니다) */
  tip: boolean;
  /** 이음 방향(a → b, 동·북 단위 벡터) — 홀로 있는 마디면 0 */
  ux: number;
  uy: number;
};

/** 두 점 사이 거리(m, 등장방형 — 수십 m 거리에서 하버사인과 차이가 1mm 미만). */
export function exploreDistanceM(a: ExploreLatLng, b: ExploreLatLng): number {
  const kx = METERS_PER_DEGREE * Math.cos(((a.lat + b.lat) / 2) * RAD);
  const dx = (b.lng - a.lng) * kx;
  const dy = (b.lat - a.lat) * METERS_PER_DEGREE;
  return Math.sqrt(dx * dx + dy * dy);
}

/** 서울 날짜 번호(1970-01-01이 0). 하루의 경계는 앱 홈과 같은 KST 자정. */
export function exploreDayOf(ms: number): number {
  return Math.floor((ms + 9 * 3600 * 1000) / 86400000);
}

/** 위치 오차(m) → 아는 길에 붙는 거리(m) */
export function exploreSnapRadiusM(acc: number | null | undefined): number {
  const rules = EXPLORE_RULES;
  if (typeof acc !== 'number' || !Number.isFinite(acc) || acc <= 0) return rules.snapMinM;
  return Math.min(rules.snapMaxM, Math.max(rules.snapMinM, acc * rules.snapAccuracyFactor));
}

function round6(value: number): number {
  return Math.round(value * 1e6) / 1e6;
}

function edgeKey(a: number, b: number): number {
  return a < b ? a * EDGE_KEY_BASE + b : b * EDGE_KEY_BASE + a;
}

function cellRow(lat: number): number {
  return Math.floor((lat * METERS_PER_DEGREE) / CELL_M);
}

/** 그 줄(row)의 가운데 위도로 정한 경도 칸 — 같은 줄의 칸은 동서로 CELL_M 이상 넓다. */
function cellCol(row: number, lng: number): number {
  const rowLat = ((row + 0.5) * CELL_M) / METERS_PER_DEGREE;
  return Math.floor((lng * METERS_PER_DEGREE * Math.cos(rowLat * RAD)) / CELL_M);
}

function cellKey(row: number, col: number): number {
  // 줄은 ±40만, 칸은 ±160만 안 — 2^21 · 2^23 범위로 옮겨 한 수로(2^53 안)
  return (row + 1048576) * 8388608 + (col + 4194304);
}

/** 고를 때: 더 가까운 것, 같으면 번호가 작은 이음(그물을 읽은 차례와 상관없이 같은 답) */
function closer(x: ExploreNear, best: ExploreNear | null): boolean {
  if (best === null || x.d < best.d) return true;
  if (x.d > best.d) return false;
  return x.a < best.a || (x.a === best.a && x.b < best.b);
}

/**
 * 한 사람의 걸은 길 그물. 서버는 사람마다 지도 조각(타일)을 읽어 채운 뒤 새 기록을 넣고 바뀐 마디·이음을 다시 적는다.
 * 앱은 기록 중에 둘레 조각을 읽어 채워 두고 같은 계산으로 '오늘 개척'을 어림한다.
 */
export class ExploreGraph {
  private readonly nodeMap = new Map<number, ExploreNode>();
  private readonly edgeMap = new Map<number, ExploreEdge>();
  private readonly grid = new Map<number, number[]>();
  private readonly neighbors = new Map<number, number[]>();
  /** 다음에 만들 마디 번호 */
  nextId: number;
  /** 바뀐(새로 생겼거나 visits가 늘어난) 마디·이음 — 저장할 것 */
  readonly dirtyNodes = new Set<number>();
  readonly dirtyEdges = new Set<number>();

  constructor(nextId: number = 1) {
    this.nextId = Math.max(1, Math.floor(nextId));
  }

  get nodeCount(): number {
    return this.nodeMap.size;
  }

  get edgeCount(): number {
    return this.edgeMap.size;
  }

  node(id: number): ExploreNode | undefined {
    return this.nodeMap.get(id);
  }

  edge(a: number, b: number): ExploreEdge | undefined {
    return this.edgeMap.get(edgeKey(a, b));
  }

  nodes(): IterableIterator<ExploreNode> {
    return this.nodeMap.values();
  }

  edges(): IterableIterator<ExploreEdge> {
    return this.edgeMap.values();
  }

  /** 마디에 붙은 이음 수(읽어 둔 것만) */
  degree(id: number): number {
    return this.neighbors.get(id)?.length ?? 0;
  }

  /** 저장해 둔 마디를 채운다(번호가 겹치면 덮는다). nextId도 그 뒤로 민다. */
  loadNode(node: ExploreNode): void {
    const existing = this.nodeMap.get(node.id);
    if (existing) this.unindex(existing);
    const copy: ExploreNode = { id: node.id, lat: node.lat, lng: node.lng, visits: node.visits, day: node.day };
    this.nodeMap.set(copy.id, copy);
    this.index(copy);
    if (copy.id >= this.nextId) this.nextId = copy.id + 1;
  }

  /** 저장해 둔 이음을 채운다. 양 끝 마디가 아직 없어도 받는다(조각 경계). */
  loadEdge(edge: ExploreEdge): void {
    if (edge.a === edge.b) return;
    const swap = edge.a > edge.b;
    const key = edgeKey(edge.a, edge.b);
    if (!this.edgeMap.has(key)) this.link(edge.a, edge.b);
    this.edgeMap.set(
      key,
      swap
        ? { a: edge.b, b: edge.a, visits: edge.visits, day: edge.day, aLat: edge.bLat, aLng: edge.bLng, bLat: edge.aLat, bLng: edge.aLng }
        : { a: edge.a, b: edge.b, visits: edge.visits, day: edge.day, aLat: edge.aLat, aLng: edge.aLng, bLat: edge.bLat, bLng: edge.bLng },
    );
  }

  /** 바뀐 것 표시를 지운다(저장한 뒤). */
  clearDirty(): void {
    this.dirtyNodes.clear();
    this.dirtyEdges.clear();
  }

  /** 반지름 안에서 가장 가까운 마디(같은 거리면 번호가 작은 것). skip이 true를 돌려주는 마디는 건너뛴다. */
  nearest(point: ExploreLatLng, radiusM: number, skip?: (id: number) => boolean): ExploreNode | null {
    let best: ExploreNode | null = null;
    let bestD = Infinity;
    this.forNodesAround(point, radiusM, id => {
      if (skip && skip(id)) return;
      const node = this.nodeMap.get(id);
      if (!node) return;
      const d = exploreDistanceM(point, node);
      if (d > radiusM) return;
      if (d < bestD || (d === bestD && best !== null && id < best.id)) {
        best = node;
        bestD = d;
      }
    });
    return best;
  }

  /**
   * 반지름 안의 이음(점에서 선분까지 수직 거리)과 이음 없이 홀로 있는 마디. 이음마다 한 번. 이음은 maxEdgeM 이하라(v4가 만든 것) 한 끝이
   * radiusM + maxEdgeM/2 안에 든다 — 그만큼의 칸을 본다.
   */
  near(point: ExploreLatLng, radiusM: number): ExploreNear[] {
    const out: ExploreNear[] = [];
    const kx = METERS_PER_DEGREE * Math.cos(point.lat * RAD);
    const seen = new Set<number>();
    this.forNodesAround(point, radiusM + EXPLORE_RULES.maxEdgeM / 2, id => {
      const node = this.nodeMap.get(id);
      if (!node) return;
      const links = this.neighbors.get(id);
      if (!links || links.length === 0) {
        const dx = (node.lng - point.lng) * kx;
        const dy = (node.lat - point.lat) * METERS_PER_DEGREE;
        const d = Math.sqrt(dx * dx + dy * dy);
        if (d <= radiusM) out.push({ a: id, b: id, t: 0, d, tip: false, ux: 0, uy: 0 });
        return;
      }
      for (const other of links) {
        const key = edgeKey(id, other);
        if (seen.has(key)) continue;
        seen.add(key);
        const edge = this.edgeMap.get(key);
        if (!edge) continue;
        // 점을 0으로 둔 동·북 m
        const ax = (edge.aLng - point.lng) * kx;
        const ay = (edge.aLat - point.lat) * METERS_PER_DEGREE;
        const vx = (edge.bLng - point.lng) * kx - ax;
        const vy = (edge.bLat - point.lat) * METERS_PER_DEGREE - ay;
        const len2 = vx * vx + vy * vy;
        const raw = len2 > 0 ? -(ax * vx + ay * vy) / len2 : 0;
        const t = raw < 0 ? 0 : raw > 1 ? 1 : raw;
        const fx = ax + vx * t;
        const fy = ay + vy * t;
        const d = Math.sqrt(fx * fx + fy * fy);
        if (d > radiusM) continue;
        const len = Math.sqrt(len2);
        // 길 끝 너머로 간 만큼이 길에서 옆으로 비킨 만큼보다 크면(곧게 이은 쪽 45° 안) 앞으로 더 간 걸음
        const beyond = raw < 0 ? -raw * len : raw > 1 ? (raw - 1) * len : 0;
        const aside = len > 0 ? Math.abs(ax * vy - ay * vx) / len : d;
        const tip = beyond > 0 && beyond >= aside && this.degree(raw < 0 ? edge.a : edge.b) === 1;
        out.push({ a: edge.a, b: edge.b, t, d, tip, ux: len > 0 ? vx / len : 0, uy: len > 0 ? vy / len : 0 });
      }
    });
    return out;
  }

  /**
   * from에서 to까지 그물을 따라가는 가장 짧은 길(마디 번호들, 양 끝 포함). maxM보다 길면 null. 같은 길이면 번호가 작은 마디를 거친다
   * (그물을 읽은 차례와 상관없이 같은 답).
   */
  route(from: number, to: number, maxM: number): number[] | null {
    if (from === to) return [from];
    const cost = new Map<number, number>([[from, 0]]);
    const prev = new Map<number, number>();
    const done = new Set<number>();
    const open: number[] = [from];
    while (open.length > 0) {
      let pick = 0;
      for (let i = 1; i < open.length; i += 1) {
        const ci = cost.get(open[i]) as number;
        const cp = cost.get(open[pick]) as number;
        if (ci < cp || (ci === cp && open[i] < open[pick])) pick = i;
      }
      const at = open[pick];
      open[pick] = open[open.length - 1];
      open.pop();
      done.add(at);
      if (at === to) break;
      const base = cost.get(at) as number;
      for (const next of this.neighbors.get(at) ?? []) {
        if (done.has(next)) continue;
        const edge = this.edgeMap.get(edgeKey(at, next));
        if (!edge) continue;
        const c = base + exploreDistanceM({ lat: edge.aLat, lng: edge.aLng }, { lat: edge.bLat, lng: edge.bLng });
        if (c > maxM) continue;
        const old = cost.get(next);
        if (old === undefined) {
          open.push(next);
          cost.set(next, c);
          prev.set(next, at);
        } else if (c < old || (c === old && at < (prev.get(next) as number))) {
          cost.set(next, c);
          prev.set(next, at);
        }
      }
    }
    if (!done.has(to)) return null;
    const path = [to];
    let at = to;
    while (at !== from) {
      at = prev.get(at) as number;
      path.push(at);
    }
    return path.reverse();
  }

  /** 새 기록을 시작한다. 점을 차례로 넣고 end()로 끝낸다. */
  beginWalk(options: { day: number }): ExploreWalk {
    return new ExploreWalk(this, options.day);
  }

  /** 기록 하나를 한 번에 넣는다(beginWalk → push → end). */
  addWalk(points: ReadonlyArray<ExplorePoint>, options: { day: number }): ExploreWalkResult {
    const walk = this.beginWalk(options);
    for (const point of points) walk.push(point);
    return walk.end();
  }

  // ── ExploreWalk만 부른다

  /** @internal */
  createNode(lat: number, lng: number, day: number): ExploreNode {
    const node: ExploreNode = { id: this.nextId, lat: round6(lat), lng: round6(lng), visits: 0, day };
    this.nextId += 1;
    this.nodeMap.set(node.id, node);
    this.index(node);
    this.dirtyNodes.add(node.id);
    return node;
  }

  /** @internal */
  bumpNode(node: ExploreNode): void {
    node.visits += 1;
    this.dirtyNodes.add(node.id);
  }

  /** @internal 이음을 만든다(없을 때). 만들었으면 true. */
  ensureEdge(a: number, b: number, day: number): { edge: ExploreEdge; created: boolean } {
    const key = edgeKey(a, b);
    const existing = this.edgeMap.get(key);
    if (existing) return { edge: existing, created: false };
    const na = this.nodeMap.get(Math.min(a, b));
    const nb = this.nodeMap.get(Math.max(a, b));
    if (!na || !nb) throw new Error('explore: edge between unknown nodes');
    const edge: ExploreEdge = {
      a: na.id,
      b: nb.id,
      visits: 0,
      day,
      aLat: na.lat,
      aLng: na.lng,
      bLat: nb.lat,
      bLng: nb.lng,
    };
    this.edgeMap.set(key, edge);
    this.link(edge.a, edge.b);
    this.dirtyEdges.add(key);
    return { edge, created: true };
  }

  /** @internal */
  bumpEdge(edge: ExploreEdge): void {
    edge.visits += 1;
    this.dirtyEdges.add(edgeKey(edge.a, edge.b));
  }

  /** 바뀐 이음 열쇠를 [작은 번호, 큰 번호]로. */
  static edgeEnds(key: number): [number, number] {
    const a = Math.floor(key / EDGE_KEY_BASE);
    return [a, key - a * EDGE_KEY_BASE];
  }

  /** 점에서 reachM 안을 덮는 칸들의 마디 번호마다 */
  private forNodesAround(point: ExploreLatLng, reachM: number, visit: (id: number) => void): void {
    const span = Math.max(1, Math.ceil(reachM / CELL_M));
    const row = cellRow(point.lat);
    for (let r = row - span; r <= row + span; r += 1) {
      const col = cellCol(r, point.lng);
      for (let c = col - span; c <= col + span; c += 1) {
        const ids = this.grid.get(cellKey(r, c));
        if (!ids) continue;
        for (const id of ids) visit(id);
      }
    }
  }

  private link(a: number, b: number): void {
    const na = this.neighbors.get(a);
    if (na) {
      if (!na.includes(b)) na.push(b);
    } else {
      this.neighbors.set(a, [b]);
    }
    const nb = this.neighbors.get(b);
    if (nb) {
      if (!nb.includes(a)) nb.push(a);
    } else {
      this.neighbors.set(b, [a]);
    }
  }

  private index(node: ExploreNode): void {
    const row = cellRow(node.lat);
    const key = cellKey(row, cellCol(row, node.lng));
    const ids = this.grid.get(key);
    if (ids) ids.push(node.id);
    else this.grid.set(key, [node.id]);
  }

  private unindex(node: ExploreNode): void {
    const row = cellRow(node.lat);
    const key = cellKey(row, cellCol(row, node.lng));
    const ids = this.grid.get(key);
    if (!ids) return;
    const at = ids.indexOf(node.id);
    if (at >= 0) ids.splice(at, 1);
    if (ids.length === 0) this.grid.delete(key);
  }
}

/** 다시 찍은 점 하나(길을 따라 stepM마다). t는 그 자리를 지난 시각(앞뒤 점 사이를 고르게) */
type StepPoint = { t: number; lat: number; lng: number; weak: boolean; restart: boolean; acc: number | null; paced: boolean };

/**
 * 쥐고 있는 걸음: drift = 아는 길 옆 띠 안, out = 띠 밖으로 나간 걸음(아직 흔들림일 수 있다), own = 방금 만든 길 끝 옆, back = 그 길과 나란히 돌아오는
 * 걸음(돌아선 왕복인지 나란한 다른 길인지 모아 본다). bandM = 그때의 띠 끝(m)
 */
type Held = { point: StepPoint; walked: number; along: number; near: ExploreNear | null; kind: 'drift' | 'out' | 'own' | 'back'; bandM: number };

/** 같은 이음을 되짚어 가는 동안: 시작한 마디·아직 세지 않은 이음([이음 열쇠, 걸은 차례의 자리])·센 이음·되짚은 거리가 repassM을 넘었는지 */
type Retrace = { start: number; pending: Array<[number, number]>; counted: Set<number>; confirmed: boolean };

/**
 * 기록 하나를 그물에 넣는 중인 상태. 점을 시각순으로 push하고 end()로 끝낸다(남은 반 칸을 마저 찍고, 쥐고 있던 걸음을 정한다).
 * 기록 중 화면은 push만 하며 result()로 지금까지를 읽는다(end 전의 마지막 반 칸·쥐고 있는 걸음은 빠진다 — 끝낸 뒤 계산의 앞부분).
 */
export class ExploreWalk {
  private readonly rules = EXPLORE_RULES;
  /** 이번 기록이 지난(visits를 올린) 마디 */
  private readonly visitedNodes = new Set<number>();
  /** 이번 기록이 지난 이음 → 센 수(passCap까지) */
  private readonly passes = new Map<number, number>();
  /** 이번 기록이 만든 마디 → 그때까지 걸은 길이(m) */
  private readonly createdAlong = new Map<number, number>();
  private readonly out = { pioneeredM: 0, onFootM: 0, newNodes: 0, newEdges: [] as Array<[number, number]>, walkEdges: [] as Array<[number, number]> };

  // 다시 찍기(resample)
  private last: ExplorePoint | null = null;
  /** 마지막으로 찍은 자리에서 지금 점까지 남은 길이(다음 찍을 자리까지 얼마 남았는지 재는 데 씀) */
  private carryM = 0;
  /** 다시 찍은 앞 점(이음의 길이·끊김 판단) */
  private lastStep: StepPoint | null = null;
  /** 길을 따라 걸은 길이(다시 찍은 점 기준) */
  private along = 0;
  /** 이번 줄기의 최근 찍은 점(걷는 방향 — 셋까지) */
  private trail: StepPoint[] = [];

  // 그물에 붙이기
  private prevNode: number | null = null;
  /** 앞 걸음이 새 마디를 만들었다(아는 길로 들어가는 이음도 개척) */
  private lastFree = false;
  /** 끊기 전 줄기의 마지막 마디 — 일시정지·위치 끊김 뒤 그 자리에서 다시 걸으면 거기서 잇는다(v2) */
  private resumeNode: number | null = null;
  /** 쥐고 있는 걸음(한 가지씩 — drift·out 묶음 또는 own 묶음) */
  private held: Held[] = [];
  private heldKind: 'drift' | 'own' | null = null;
  /** 같은 이음을 되짚어 가는 중 */
  private retrace: Retrace | null = null;
  /** 지금 정하는 걸음이 걸음으로 확인한 걸음인지(되짚기를 셀 거리 — repassPacedM) */
  private paced = false;
  /** 걸은 차례(ExploreWalkResult.walkPath)와 그 끝 마디(붓을 들었으면 null) */
  private readonly path: number[] = [];
  private pathAt: number | null = null;
  /** 그물에 넣은(쥐지 않은) 마지막 걸음의 시각 — 기록 중 그림이 여기까지는 그물 선, 그 뒤는 기록 선 */
  private settledT = -Infinity;
  /** 결과가 바뀔 때마다 +1(기록 중 그림을 다시 만들지 볼 때) */
  private revisionCount = 0;
  private ended = false;

  constructor(
    private readonly graph: ExploreGraph,
    private readonly day: number,
  ) {}

  /** 점 하나. 걷고 뛴 점이 아니면 거기서 길이 끊긴다. */
  push(point: ExplorePoint): void {
    if (this.ended) return;
    if (!Number.isFinite(point.lat) || !Number.isFinite(point.lng) || !Number.isFinite(point.t)) return;
    const last = this.last;
    if (last !== null && point.t <= last.t) return;
    this.last = point;
    if (!point.onFoot) {
      this.finishRun(last);
      return;
    }
    const linked =
      last !== null &&
      last.onFoot &&
      point.restart !== true &&
      (point.t - last.t) / 1000 <= this.rules.linkMaxS &&
      exploreDistanceM(last, point) <= this.rules.linkMaxM;
    if (!linked) {
      // 앞 줄기를 끝내고, 새 줄기의 첫 점은 그대로 찍는다
      this.finishRun(last);
      this.step({ t: point.t, lat: point.lat, lng: point.lng, weak: point.weak === true, restart: true, acc: accOf(point), paced: point.paced === true });
      this.carryM = 0;
      return;
    }
    // 앞 점 → 이번 점 선분을 따라 stepM마다 찍는다
    const segment = exploreDistanceM(last, point);
    if (segment <= 0) return;
    const acc = largerAcc(accOf(last), accOf(point));
    let at = this.rules.stepM - this.carryM;
    while (at <= segment + 1e-9) {
      const f = at / segment;
      this.step({
        t: last.t + (point.t - last.t) * f,
        lat: last.lat + (point.lat - last.lat) * f,
        lng: last.lng + (point.lng - last.lng) * f,
        weak: point.weak === true,
        restart: false,
        acc,
        paced: point.paced === true,
      });
      at += this.rules.stepM;
    }
    this.carryM = segment - (at - this.rules.stepM);
  }

  /** 지금까지(끝의 반 칸·쥐고 있는 걸음 빼고). */
  result(): ExploreWalkResult {
    const walkPasses: Array<[number, number, number]> = [];
    for (const [a, b] of this.out.walkEdges) {
      const n = this.passes.get(a * EDGE_KEY_BASE + b) ?? 1;
      if (n >= 2) walkPasses.push([a, b, n]);
    }
    return { ...this.out, newEdges: this.out.newEdges.slice(), walkEdges: this.out.walkEdges.slice(), walkPasses, walkPath: this.path.slice() };
  }

  /** 끝낸다: 마지막 점이 찍은 자리에서 stepM의 반 넘게 떨어져 있으면 그 점도 찍고, 쥐고 있던 걸음을 정한다. */
  end(): ExploreWalkResult {
    if (!this.ended) {
      this.stepTail(this.last);
      this.releaseAtBreak();
      this.retrace = null;
      this.ended = true;
    }
    return this.result();
  }

  /** 결과가 바뀐 수(기록 중 그림이 다시 만들지 볼 때) */
  get revision(): number {
    return this.revisionCount;
  }

  /** 그물에 넣은 마지막 걸음의 시각(ms — 아직 없으면 -Infinity)과 그때 있던 마디(없으면 null) */
  settled(): { t: number; node: number | null } {
    return { t: this.settledT, node: this.prevNode };
  }

  /** 이번 기록이 지난 수(1~passCap, 지나지 않았으면 0) */
  passesOf(a: number, b: number): number {
    return this.passes.get(edgeKey(a, b)) ?? 0;
  }

  /** 줄기의 마지막 점이 찍은 자리에서 stepM의 반 넘게 떨어져 있으면 그 점도 찍는다(끝낼 때·줄기가 끊길 때) */
  private stepTail(last: ExplorePoint | null): void {
    if (last !== null && last.onFoot && this.lastStep !== null && this.carryM >= this.rules.stepM / 2) {
      this.step({ t: last.t, lat: last.lat, lng: last.lng, weak: last.weak === true, restart: false, acc: accOf(last), paced: last.paced === true });
    }
  }

  /** 줄기를 끝낸다: 꼬리를 찍고(v3 — 마지막 마디가 멈춘 자리에 있어야 다시 걸을 때 거기서 잇는다) 끊는다 */
  private finishRun(last: ExplorePoint | null): void {
    this.stepTail(last);
    this.breakRun();
  }

  private breakRun(): void {
    this.releaseAtBreak();
    this.retrace = null;
    if (this.prevNode !== null) this.resumeNode = this.prevNode;
    this.lastStep = null;
    this.prevNode = null;
    this.pathAt = null;
    this.lastFree = false;
    this.trail = [];
    this.carryM = 0;
  }

  /**
   * 줄기가 끊기거나 끝날 때 쥐고 있던 걸음: 띠 안의 걸음은 아는 길로, 막 만든 길 끝 옆의 걸음은 — 돌아오는 걸음이 있으면 그 가운데값으로(돌아서자마자
   * 끝내도 끝에 가시가 남지 않게), 없으면 새 길로(앞으로 걸은 것).
   */
  private releaseAtBreak(): void {
    if (this.heldKind !== 'own') {
      this.release('ride');
      return;
    }
    this.release(this.uTurnVerdict() ?? 'new');
  }

  /** 쥐고 있는 돌아오는 걸음(back)의 거리 가운데값이 띠(붙는 거리 + driftBandM) 안이면 ride(돌아선 왕복), 아니면 new — 돌아오는 걸음이 없으면 null */
  private uTurnVerdict(): 'ride' | 'new' | null {
    const back = this.held.filter(held => held.kind === 'back' && held.near !== null);
    if (back.length === 0) return null;
    const ds = back.map(held => (held.near as ExploreNear).d).sort((p, q) => p - q);
    const half = Math.floor(ds.length / 2);
    const mid = ds.length % 2 === 1 ? ds[half] : (ds[half - 1] + ds[half]) / 2;
    const snapM = exploreSnapRadiusM(back[back.length - 1].point.acc);
    return mid <= snapM + this.rules.driftBandM ? 'ride' : 'new';
  }

  private step(point: StepPoint): void {
    const rules = this.rules;
    const previous = this.lastStep;
    this.paced = point.paced;
    let walked = 0;
    if (previous !== null && !point.restart) {
      walked = exploreDistanceM(previous, point);
      this.along += walked;
      this.out.onFootM += walked;
    }
    this.lastStep = point;
    if (point.restart) this.trail = [];
    this.trail.push(point);
    if (this.trail.length > 3) this.trail.shift();
    const along = this.along;
    const snapM = exploreSnapRadiusM(point.acc);
    const entry: Held = { point, walked, along, near: null, kind: 'drift', bandM: snapM + rules.driftBandM };

    // 일시정지·끊김 뒤 첫 점: 끊기 전 마지막 마디 곁이면 거기서 잇는다
    if (point.restart && this.resumeNode !== null) {
      const resume = this.resumeNode;
      const node = this.graph.node(resume);
      const d = node ? exploreDistanceM(point, node) : Infinity;
      if (d <= snapM) {
        this.prevNode = resume;
        this.lastFree = false;
        this.visitNode(resume);
        this.traceStart(resume);
        this.settle(point.t);
        return;
      }
      if (d <= snapM + rules.driftBandM) {
        this.prevNode = resume;
        this.lastFree = false;
        this.createFree(entry);
        return;
      }
    }

    const searchM = snapM + rules.ownBandM + rules.driftBandM;
    let found = this.candidates(point, along, searchM);
    let bandM = this.bandFor(found.valid, snapM);
    let kind = this.kindOf(point, found, snapM, bandM);
    if (this.held.length > 0) {
      // 쥐고 있던 걸음부터 정한다(이 점이 무엇인지에 따라). 새 마디가 생겼으면 이 점을 다시 본다
      const settled = this.settleHeld(kind, found.valid, entry, bandM);
      if (settled === 'taken') return;
      if (settled === 'changed') {
        found = this.candidates(point, along, searchM);
        bandM = this.bandFor(found.valid, snapM);
        kind = this.kindOf(point, found, snapM, bandM);
      }
    }
    if (kind === 'snap' && found.valid !== null) {
      // 아는 길에 붙음
      this.moveTo(found.valid, walked, along);
      this.settle(point.t);
      return;
    }
    if (kind === 'drift' && found.valid !== null) {
      // 옆으로 흔들린 띠: 쥔다. 띠 안을 오래 따라가면 아는 길
      entry.near = found.valid;
      entry.bandM = bandM;
      this.hold(entry, 'drift');
      if (this.heldLength('drift') >= rules.driftHoldM) this.release('ride');
      return;
    }
    if (kind === 'back' && found.valid !== null) {
      // 막 만든 길과 나란히 돌아오는 걸음: uTurnPoints만큼 모아 거리의 가운데값이 띠(붙는 거리 + driftBandM) 안이면 돌아선 왕복(그 길로), 아니면
      // 나란한 다른 길(새 길로 — 좁은 블록을 돌아 옆 골목으로 돌아온 것)
      entry.near = found.valid;
      entry.kind = 'back';
      this.hold(entry, 'own');
      if (this.held.filter(held => held.kind === 'back').length >= rules.uTurnPoints) this.release(this.uTurnVerdict() ?? 'new');
      return;
    }
    if (kind === 'own' && found.own !== null) {
      // 방금 만든 길 끝 옆(옆이나 뒤 — 앞으로 나아간 걸음은 아니다): 돌아서는지 한두 걸음 더 본다
      entry.near = found.own;
      entry.kind = 'own';
      this.hold(entry, 'own');
      if (this.heldLength('own') >= rules.ownHoldM) this.release('new');
      return;
    }
    this.createFree(entry);
  }

  /** 점 둘레에서 붙어도 되는 가장 가까운 자리(valid)와, 이번 기록이 막 만들어 아직 붙지 못하는 가장 가까운 자리(own) */
  private candidates(point: StepPoint, along: number, radiusM: number): { valid: ExploreNear | null; own: ExploreNear | null } {
    let valid: ExploreNear | null = null;
    let own: ExploreNear | null = null;
    for (const near of this.graph.near(point, radiusM)) {
      if (this.counts(near, along)) {
        if (closer(near, valid)) valid = near;
      } else if (closer(near, own)) {
        own = near;
      }
    }
    return { valid, own };
  }

  /**
   * 띠의 끝(m): 아는 길 위를 가던 중이거나 띠를 따라가는 중이면 붙는 거리 + driftBandM(이번 기록이 만든 길이면 + ownBandM — 돌아선 왕복은 갈 때·올 때
   * 위치가 따로 흔들린다), 새 길을 걷다 아는 길과 나란히 들어오면 + freeBandM(좁게 — 25m 옆 다른 골목이 흔들림으로 가까워져도 합치지 않게). 막 만든
   * 길 끝 옆을 쥐고 있는 동안은 붙는 거리만(돌아오는 걸음은 back으로 모아 가운데값으로 정한다 — 길을 건너 돌아온 왕복과 좁은 블록을 돈 길은 생김새가
   * 같아 한 점으로 가리면 틀린다).
   */
  private bandFor(near: ExploreNear | null, snapM: number): number {
    const rules = this.rules;
    const riding = this.prevNode !== null && !this.lastFree;
    const ownLine = near !== null && this.createdAt(near) !== undefined;
    if (riding || this.heldKind === 'drift') return snapM + (ownLine ? rules.ownBandM : rules.driftBandM);
    // 방금 만든 길 끝 옆을 쥐고 있는 동안은 띠로 바로 붙지 않는다 — 돌아오는 걸음을 모아 본다(back)
    if (this.heldKind === 'own') return snapM;
    return snapM + rules.freeBandM;
  }

  /**
   * 이 점이 무엇인지: snap = 아는 길에 붙음, drift = 아는 길 옆 띠(그 길 위를 가던 중·이미 띠를 따라가는 중·줄기의 첫 점이거나 새 길에서
   * 그 길과 나란히 들어옴 — 막다른 끝에서 앞으로 더 간 걸음은 아님), back = 방금 만든 길 끝 옆을 쥐고 있는데 그 길과 나란히 돌아오는 걸음(ownBandM
   * 안), own = 방금 만든 길 끝의 옆이나 뒤(앞으로 나아간 걸음이 아님), free = 새 길.
   */
  private kindOf(point: StepPoint, found: { valid: ExploreNear | null; own: ExploreNear | null }, snapM: number, bandM: number): 'snap' | 'drift' | 'back' | 'own' | 'free' {
    const rules = this.rules;
    const { valid, own } = found;
    if (valid !== null && valid.d <= snapM) return 'snap';
    const riding = this.prevNode !== null && !this.lastFree;
    const first = this.prevNode === null && this.trail.length < 2;
    if (valid !== null && valid.d <= bandM && !valid.tip && (this.heldKind === 'drift' || riding || first || this.parallel(valid))) return 'drift';
    if (
      this.heldKind === 'own' &&
      valid !== null &&
      valid.d <= snapM + rules.ownBandM &&
      !valid.tip &&
      this.createdAt(valid) !== undefined &&
      this.parallel(valid)
    ) {
      return 'back';
    }
    if (own !== null && own.d <= snapM + rules.driftBandM && !own.tip && !point.weak && this.heldKind !== 'drift') return 'own';
    return 'free';
  }

  /**
   * 쥐고 있던 걸음을 이 점에 따라 정한다. none = 그대로 쥔다, moved = 그물을 따라 옮겼다(새 마디 없음), changed = 새 마디가 생겼다(이 점을
   * 다시 본다), taken = 이 점도 쥐었다(띠 밖으로 나간 걸음 — 아직 흔들림일 수 있다).
   *  - 방금 만든 길 끝 옆을 쥐고 있었다: 이번 기록이 만든 길로 돌아와 붙으면 돌아선 것(쥔 걸음도 그 길로 — 끝에 가시가 생기지 않게), 다른 길이면
   *    앞으로 걸은 것(새 길로).
   *  - 띠를 쥐고 있었다: 다시 붙으면 아는 길. 띠 밖으로 나가면(띠 끝 + driftBandM 안) exitConfirmM까지는 더 본다 — 그 전에 띠로 돌아오면 흔들림이라
   *    계속 쥐고, 그만큼 잇달아 나갔거나 더 멀리 나가면 끝의 먼 걸음부터 새 길(나란한 짧은 혹이 생기지 않게).
   */
  private settleHeld(kind: 'snap' | 'drift' | 'back' | 'own' | 'free', valid: ExploreNear | null, entry: Held, bandM: number): 'none' | 'moved' | 'changed' | 'taken' {
    if (this.heldKind === 'own') {
      if (kind === 'own' || kind === 'back') return 'none';
      if ((kind === 'snap' || kind === 'drift') && valid !== null && this.isOwnTarget(valid)) {
        this.release('ride');
        return 'moved';
      }
      this.release('new');
      return 'changed';
    }
    if (kind === 'drift') return 'none';
    if (kind === 'snap') {
      this.release('ride');
      return 'moved';
    }
    const out = valid !== null && valid.d <= bandM + this.rules.driftBandM;
    if (out) {
      entry.near = valid;
      entry.kind = 'out';
      entry.bandM = bandM;
      this.held.push(entry);
      if (this.trailingOutM() < this.rules.exitConfirmM) return 'taken';
    }
    this.release('split');
    return out ? 'taken' : 'changed';
  }

  /** 쥔 걸음 끝에서 잇달아 띠 밖으로 나간 길이(m) */
  private trailingOutM(): number {
    let sum = 0;
    for (let i = this.held.length - 1; i >= 0 && this.held[i].kind === 'out'; i -= 1) sum += this.held[i].walked;
    return sum;
  }

  /** 이 자리에 붙어도 되는지: 이번 기록이 만든 길이면 만든 뒤 걸은 길이에 견줘 가까워야 한다(돌아섬) */
  private counts(near: ExploreNear, along: number): boolean {
    const created = this.createdAt(near);
    if (created === undefined) return true;
    return near.d <= this.rules.ownRatio * (along - created);
  }

  /** 그 자리를 이번 기록이 만들 때까지 걸은 길이(이번 기록이 만든 길이 아니면 undefined) */
  private createdAt(near: ExploreNear): number | undefined {
    const ca = this.createdAlong.get(near.a);
    const cb = near.a === near.b ? ca : this.createdAlong.get(near.b);
    if (ca !== undefined && cb !== undefined) return ca + (cb - ca) * near.t;
    return near.t <= 0.5 ? ca : cb;
  }

  /** 걷는 방향(최근 찍은 점들)이 그 이음과 나란한지 */
  private parallel(near: ExploreNear): boolean {
    const trail = this.trail;
    if (trail.length < 2 || (near.ux === 0 && near.uy === 0)) return false;
    const from = trail[0];
    const to = trail[trail.length - 1];
    const dx = (to.lng - from.lng) * METERS_PER_DEGREE * Math.cos(to.lat * RAD);
    const dy = (to.lat - from.lat) * METERS_PER_DEGREE;
    const len = Math.sqrt(dx * dx + dy * dy);
    if (len < this.rules.stepM / 2) return false;
    return Math.abs((dx * near.ux + dy * near.uy) / len) >= PARALLEL_COS;
  }

  /** 붙을 마디: 가까운 끝(읽어 둔 마디만) */
  private targetOf(near: ExploreNear): number | null {
    const first = near.t <= 0.5 ? near.a : near.b;
    if (this.graph.node(first)) return first;
    const second = first === near.a ? near.b : near.a;
    return this.graph.node(second) ? second : null;
  }

  private isOwnTarget(near: ExploreNear): boolean {
    const target = this.targetOf(near);
    return target !== null && this.createdAlong.has(target);
  }

  private hold(entry: Held, kind: 'drift' | 'own'): void {
    this.held.push(entry);
    this.heldKind = kind;
  }

  /** 쥔 걸음의 길이(m) — kind를 주면 그 걸음만 */
  private heldLength(kind?: Held['kind']): number {
    let sum = 0;
    for (const entry of this.held) if (kind === undefined || entry.kind === kind) sum += entry.walked;
    return sum;
  }

  /**
   * 쥐고 있던 걸음을 정한다. ride = 붙을 자리(near)로 그물을 따라간다, new = 그 자리에 새 마디, split = 끝에서부터 띠 가운데(붙는 거리와
   * 그때 띠 끝의 가운데)보다 먼 걸음은 새 길, 그 앞은 아는 길(갈라진 자리를 뒤로 밀지 않게).
   */
  private release(mode: 'ride' | 'new' | 'split'): void {
    const held = this.held;
    if (held.length === 0) return;
    this.held = [];
    this.heldKind = null;
    let firstNew = mode === 'ride' ? held.length : mode === 'new' ? 0 : held.length;
    if (mode === 'split') {
      while (firstNew > 0) {
        const entry = held[firstNew - 1];
        const snapM = exploreSnapRadiusM(entry.point.acc);
        if (entry.near === null || entry.near.d <= (snapM + entry.bandM) / 2) break;
        firstNew -= 1;
      }
    }
    // 쥐고 있던 걸음은 그 걸음의 것으로 센다(되짚기 거리 — repassPacedM), 끝나면 지금 걸음의 것으로
    const pacedNow = this.paced;
    for (let i = 0; i < held.length; i += 1) {
      const entry = held[i];
      this.paced = entry.point.paced;
      if (i < firstNew && entry.near !== null) {
        this.moveTo(entry.near, entry.walked, entry.along);
        this.settle(entry.point.t);
      } else {
        this.createFree(entry);
      }
    }
    this.paced = pacedNow;
  }

  /** 붙을 자리까지 그물을 따라간다(지나간 이음을 센다). 그물로 이어지지 않으면 새 이음 — 새 길에서 들어오는 걸음이면 개척 */
  private moveTo(near: ExploreNear, walked: number, along: number): void {
    const target = this.targetOf(near);
    if (target === null) return;
    const from = this.prevNode;
    if (from === null) {
      this.visitNode(target);
      this.prevNode = target;
      this.traceStart(target);
      this.lastFree = false;
      this.revisionCount += 1;
      return;
    }
    if (from === target) return;
    const a = this.graph.node(from);
    const b = this.graph.node(target);
    const straight = a && b ? exploreDistanceM(a, b) : 0;
    const rules = this.rules;
    const path = this.graph.route(from, target, Math.min(rules.routeMaxM, Math.max(straight * rules.routeFactor, straight + rules.routeSlackM)));
    if (path !== null) {
      for (let i = 1; i < path.length; i += 1) {
        this.visitEdge(path[i - 1], path[i]);
        this.visitNode(path[i]);
      }
    } else {
      this.connect(from, target, walked, along, this.lastFree);
    }
    this.prevNode = target;
    this.lastFree = false;
    this.revisionCount += 1;
  }

  /** 그 자리에 새 마디를 만들어 앞 마디와 잇는다(처음 걷는 길 — 개척). 약한 위치면 만들지 않고 줄기를 끊는다 */
  private createFree(entry: Held): void {
    const { point } = entry;
    this.settle(point.t);
    if (point.weak) {
      this.prevNode = null;
      this.pathAt = null;
      this.lastFree = false;
      this.retrace = null;
      return;
    }
    const node = this.graph.createNode(point.lat, point.lng, this.day);
    this.createdAlong.set(node.id, entry.along);
    this.out.newNodes += 1;
    this.visitNode(node.id);
    if (this.prevNode !== null) this.connect(this.prevNode, node.id, entry.walked, entry.along, true);
    else this.traceStart(node.id);
    this.prevNode = node.id;
    this.lastFree = true;
    this.revisionCount += 1;
  }

  /** 두 마디를 새 이음으로(maxEdgeM보다 길면 사이에 마디). pioneer면 이 걸음의 거리가 개척 */
  private connect(a: number, b: number, walked: number, along: number, pioneer: boolean): void {
    const graph = this.graph;
    const na = graph.node(a);
    const nb = graph.node(b);
    if (!na || !nb) return;
    const pieces = Math.max(1, Math.ceil(exploreDistanceM(na, nb) / this.rules.maxEdgeM - 1e-9));
    let from = a;
    let created = false;
    for (let i = 1; i < pieces; i += 1) {
      const f = i / pieces;
      const mid = graph.createNode(na.lat + (nb.lat - na.lat) * f, na.lng + (nb.lng - na.lng) * f, this.day);
      this.createdAlong.set(mid.id, along);
      this.out.newNodes += 1;
      created = this.visitEdge(from, mid.id) || created;
      this.visitNode(mid.id);
      from = mid.id;
    }
    created = this.visitEdge(from, b) || created;
    if (pioneer && created) this.out.pioneeredM += walked;
  }

  private visitNode(id: number): void {
    if (this.visitedNodes.has(id)) return;
    const node = this.graph.node(id);
    if (!node) return;
    this.visitedNodes.add(id);
    this.graph.bumpNode(node);
  }

  /**
   * 이음 하나를 from → to로 지난다(없으면 만든다). 처음이면 한 번, 이미 지난 이음이면 되짚어 가는 중으로 모아 두었다가 되짚기 시작한 마디에서
   * repassM(걸음으로 확인한 걸음이면 repassPacedM) 넘게 멀어지면 그때까지의 것을 한 번씩 더 센다(passCap까지). 처음 지나는 이음이 나오면 되짚기가
   * 끝난다(세지 않은 것은 버린다 — 몇 m 뒤로 섰다 다시 간 것). 되짚기 안에서 같은 이음으로 다시 오면(또 돌아섬) 새 되짚기다. 걸은 차례에는 세든
   * 안 세든 적는다(센 걸음만 양수). 새로 만들었으면 true.
   */
  private visitEdge(from: number, to: number): boolean {
    const graph = this.graph;
    const { edge, created } = graph.ensureEdge(from, to, this.day);
    if (created) this.out.newEdges.push([edge.a, edge.b]);
    const key = edge.a * EDGE_KEY_BASE + edge.b;
    const at = this.trace(from, to);
    const count = this.passes.get(key);
    if (count === undefined) {
      this.passes.set(key, 1);
      this.out.walkEdges.push([edge.a, edge.b]);
      graph.bumpEdge(edge);
      this.retrace = null;
      this.countStep(at);
      return created;
    }
    // 이 되짚기가 이미 지난 이음으로 다시 왔으면 또 돌아선 것 — 새 되짚기(왕복을 거듭하면 그만큼 센다)
    let retrace = this.retrace;
    if (retrace !== null && retrace.counted.has(key)) retrace = null;
    if (retrace === null) {
      retrace = { start: from, pending: [], counted: new Set(), confirmed: false };
      this.retrace = retrace;
    }
    retrace.counted.add(key);
    if (retrace.confirmed) {
      if (this.addPass(key)) this.countStep(at);
    } else {
      retrace.pending.push([key, at]);
    }
    if (!retrace.confirmed) {
      const start = graph.node(retrace.start);
      const here = graph.node(to);
      const repassM = this.paced ? this.rules.repassPacedM : this.rules.repassM;
      if (start && here && exploreDistanceM(start, here) >= repassM) {
        retrace.confirmed = true;
        for (const [pending, index] of retrace.pending) if (this.addPass(pending)) this.countStep(index);
        retrace.pending = [];
      }
    }
    return created;
  }

  /** 지난 수를 하나 올린다(passCap까지). 올렸으면 true */
  private addPass(key: number): boolean {
    const count = this.passes.get(key) ?? 0;
    if (count >= this.rules.passCap) return false;
    const [a, b] = ExploreGraph.edgeEnds(key);
    const edge = this.graph.edge(a, b);
    if (!edge) return false;
    this.passes.set(key, count + 1);
    this.graph.bumpEdge(edge);
    this.revisionCount += 1;
    return true;
  }

  /** 붓을 든 뒤 이 마디에서 새 획을 시작한다(이미 이 마디에서 그리는 중이면 그대로) */
  private traceStart(node: number): void {
    if (this.pathAt === node) return;
    this.path.push(0, node);
    this.pathAt = node;
  }

  /** 걸은 차례에 from → to를 적고 그 칸의 자리를 돌려준다 — 세지 않은 걸음(음수)으로 적고 세면 countStep이 양수로. 붓이 from에 없으면 from에서 새 획 */
  private trace(from: number, to: number): number {
    if (this.pathAt !== from) this.path.push(0, from);
    this.path.push(-to);
    this.pathAt = to;
    return this.path.length - 1;
  }

  /** 걸은 차례의 그 칸을 센 걸음으로 */
  private countStep(at: number): void {
    this.path[at] = Math.abs(this.path[at]);
  }

  private settle(t: number): void {
    if (t > this.settledT) this.settledT = t;
  }
}

function accOf(point: ExplorePoint): number | null {
  const acc = point.acc;
  return typeof acc === 'number' && Number.isFinite(acc) && acc > 0 ? acc : null;
}

function largerAcc(a: number | null, b: number | null): number | null {
  if (a === null) return b;
  if (b === null) return a;
  return a > b ? a : b;
}
