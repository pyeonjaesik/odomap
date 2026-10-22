// Golden-fixture generator for the Kotlin port (core/src/test/resources/golden/*.json).
//
// Runs the ORIGINAL TypeScript explore algorithm (verbatim copies under tools/ts/, synced from /Users/dmyeon/clientApp by
// tools/sync-ts.sh — nothing is written into clientApp) over fake paths and dumps every observable output:
//   - per walk: the ExploreWalkSummary (pioneered/onFoot metres, new/walked edges, walkPasses, walkPath, on-foot stats)
//   - the smoothed samples (PathSmoother → OnFootClassifier input) and the on-foot decisions (OnFootClassifier → ExploreWalk input)
//   - the final graph (nodes/edges in Map insertion order, nextId, dirty sets)
//   - z14 tile split, drawn lines (raw / smooth+nodes / simplified), line moves of the last walk
//   - extra probes: graph.near() / route() at fixed points, tile math, smoothExploreCoords
// Usage: (cd tools && npm install && npm run golden)
import { ExploreGraph, ExploreWalk, exploreDayOf, type ExplorePoint, type ExploreWalkResult } from './ts/src/services/run/explore/exploreGraph';
import { computeExploreWalk, ExploreRecorder, type ExploreWalkSummary } from './ts/src/services/run/explore/explorePipeline';
import { OnFootClassifier, type OnFootMotion, type OnFootSample } from './ts/src/services/run/explore/onFoot';
import {
  exploreLineMoves,
  exploreLines,
  exploreTileBounds,
  exploreTileKey,
  exploreTileOf,
  exploreTilesAround,
  exploreTilesOfGraph,
  loadExploreTile,
  smoothExploreCoords,
  type ExploreLine,
} from './ts/src/services/run/explore/exploreTile';
import type { RawPoint, RunPause } from './ts/src/services/run/runTrack';
import { pathPoints, plane, rng, shiftTo, T0 } from './ts/jest/testing/exploreTestPaths';
import * as fs from 'fs';
import * as path from 'path';

type Via = ReadonlyArray<readonly [number, number]>;
const DAY_MS = 86400e3;

// ── capture hooks (prototype patches — the algorithm code itself is untouched)
let capture: { samples: unknown[]; decisions: unknown[] } | null = null;
const origClassifierPush = OnFootClassifier.prototype.push;
OnFootClassifier.prototype.push = function (this: OnFootClassifier, sample: OnFootSample) {
  capture?.samples.push([sample.t, sample.lat, sample.lng, sample.speed, sample.weak, sample.restart, sample.acc ?? null]);
  return origClassifierPush.call(this, sample);
};
const origWalkPush = ExploreWalk.prototype.push;
ExploreWalk.prototype.push = function (this: ExploreWalk, point: ExplorePoint) {
  capture?.decisions.push([point.t, point.lat, point.lng, point.onFoot, point.weak === true, point.restart === true, point.acc ?? null, point.paced === true]);
  return origWalkPush.call(this, point);
};

// ── dump helpers
const fin = (v: number) => (Number.isFinite(v) ? v : null);

function dumpSummary(s: ExploreWalkSummary | ExploreWalkResult): Record<string, unknown> {
  return JSON.parse(JSON.stringify(s));
}

function dumpGraph(graph: ExploreGraph) {
  return {
    nextId: graph.nextId,
    nodes: [...graph.nodes()].map(n => [n.id, n.lat, n.lng, n.visits, n.day]),
    edges: [...graph.edges()].map(e => [e.a, e.b, e.visits, e.day, e.aLat, e.aLng, e.bLat, e.bLng]),
    dirtyNodes: [...graph.dirtyNodes],
    dirtyEdges: [...graph.dirtyEdges],
  };
}

function dumpLines(lines: ExploreLine[]) {
  return lines.map(l => ({ level: l.level, coords: l.coords, ...(l.nodes ? { nodes: l.nodes, stops: l.stops } : {}) }));
}

function dumpDrawing(graph: ExploreGraph, last: ExploreWalkResult | null) {
  const tiles = [...exploreTilesOfGraph(graph).entries()];
  const out: Record<string, unknown> = {
    tiles,
    linesRaw: dumpLines(exploreLines(graph.edges())),
    linesSmooth: dumpLines(exploreLines(graph.edges(), { smooth: true, nodes: true })),
    linesSimplified: dumpLines(exploreLines(graph.edges(), { simplifyM: 5, minLengthM: 20 })),
  };
  if (last) {
    const edges = last.walkEdges.map(([a, b]) => ({ ...(graph.edge(a, b) as NonNullable<ReturnType<ExploreGraph['edge']>>), visits: 1 }));
    const lines = exploreLines(edges, { smooth: true, nodes: true });
    out.walkLines = dumpLines(lines);
    out.walkMoves = exploreLineMoves(lines, last.walkPath);
  }
  return out;
}

/** near()/route() probes on the final graph: a few query points around the walked area */
function dumpProbes(graph: ExploreGraph, probes: ReadonlyArray<readonly [number, number]>) {
  const near = probes.map(([x, y]) => {
    const p = plane.toLatLng({ x, y });
    const found = graph.near(p, 30).map(n => [n.a, n.b, n.t, n.d, n.tip, n.ux, n.uy]);
    const nearest = graph.nearest(p, 30);
    return { lat: p.lat, lng: p.lng, near: found, nearest: nearest ? nearest.id : null };
  });
  const ids = [...graph.nodes()].map(n => n.id);
  const routes: unknown[] = [];
  for (let i = 0; i < ids.length && routes.length < 12; i += Math.max(1, Math.floor(ids.length / 12))) {
    const from = ids[i];
    const to = ids[(i * 7 + 3) % ids.length];
    routes.push([from, to, 80, graph.route(from, to, 80)]);
    routes.push([from, to, 400, graph.route(from, to, 400)]);
  }
  return { near, routes };
}

type WalkInput = {
  startedAt: number;
  points: RawPoint[];
  pauses?: RunPause[];
  motion?: OnFootMotion;
};

function runPipeline(graph: ExploreGraph, input: WalkInput) {
  capture = { samples: [], decisions: [] };
  const summary = computeExploreWalk(graph, input);
  const out = { input, summary: dumpSummary(summary), samples: capture.samples, decisions: capture.decisions };
  capture = null;
  return { summary, out };
}

type Fixture = Record<string, unknown> & { name: string; kind: string };
const fixtures: Fixture[] = [];

/** walks applied in order to one graph through computeExploreWalk */
function pipelineFixture(name: string, inputs: WalkInput[], opts: { probes?: ReadonlyArray<readonly [number, number]>; drawing?: boolean } = {}) {
  const graph = new ExploreGraph();
  const walks: unknown[] = [];
  let last: ExploreWalkSummary | null = null;
  for (const input of inputs) {
    graph.clearDirty();
    const { summary, out } = runPipeline(graph, input);
    walks.push(out);
    last = summary;
  }
  fixtures.push({
    name,
    kind: 'pipeline',
    walks,
    graph: dumpGraph(graph),
    ...(opts.drawing === false ? {} : dumpDrawing(graph, last)),
    ...(opts.probes ? { probes: dumpProbes(graph, opts.probes) } : {}),
  });
}

function days(walks: ReadonlyArray<{ via: Via; noiseM?: number; seed?: number; acc?: number; speed?: number; steps?: number }>): WalkInput[] {
  return walks.map((w, i) => {
    const t0 = T0 + i * DAY_MS;
    const points = pathPoints({ via: w.via, speed: w.speed ?? 1.4, noiseM: w.noiseM ?? 3, seed: w.seed ?? i + 1, acc: w.acc ?? 5, t0 });
    return { startedAt: t0, points, ...(w.steps !== undefined ? { motion: { steps: stepsFor(points, w.steps) } } : {}) };
  });
}

/** exploreWalkPath.test stepsFor: cumulative steps every 5 s at a cadence */
function stepsFor(points: ReadonlyArray<RawPoint>, cadence = 110): Array<[number, number]> {
  const out: Array<[number, number]> = [];
  if (points.length === 0) return out;
  const t0 = points[0].t;
  const t1 = points[points.length - 1].t;
  for (let t = t0; t <= t1 + 1; t += 5000) out.push([t, ((t - t0) / 60000) * cadence]);
  return out;
}

// ─────────────────────────────── pipeline scenarios (explorePipeline / exploreThick / exploreWalkPath / exploreMotion / exploreStill tests)

pipelineFixture('p01-clean-1km', [{ startedAt: T0, points: pathPoints({ via: [[0, 0], [600, 0], [600, 400]], speed: 1.4 }) }], {
  probes: [[0, 0], [300, 5], [600, 200], [610, 410], [100, -15], [598, 3]],
});

{
  const t1 = T0 + DAY_MS;
  pipelineFixture('p02-same-street-noise', [
    { startedAt: T0, points: pathPoints({ via: [[0, 0], [800, 0]], speed: 1.4, noiseM: 4, seed: 3 }) },
    { startedAt: t1, points: pathPoints({ via: [[0, 0], [800, 0]], speed: 1.4, noiseM: 4, seed: 9, t0: t1 }) },
  ], { probes: [[0, 0], [400, 0], [400, 10], [800, 3]] });
}

pipelineFixture('p03-run-2km', [{ startedAt: T0, points: pathPoints({ via: [[0, 0], [2000, 0]], speed: 3.2 }) }], { drawing: false });

{
  const walkA = pathPoints({ via: [[0, 0], [300, 0]], speed: 1.4 });
  const ride = shiftTo(pathPoints({ via: [[300, 0], [3300, 0]], speed: 11 }), walkA[walkA.length - 1].t + 1000);
  const walkB = shiftTo(pathPoints({ via: [[3300, 0], [3600, 0]], speed: 1.4 }), ride[ride.length - 1].t + 1000);
  const t1 = T0 + DAY_MS;
  pipelineFixture('p04-car-ride-middle', [
    { startedAt: T0, points: [...walkA, ...ride.slice(1), ...walkB.slice(1)] },
    { startedAt: t1, points: pathPoints({ via: [[0, 0], [300, 0]], speed: 1.4, t0: t1 }) },
  ]);
}

{
  const walkA = pathPoints({ via: [[0, 0], [300, 0]], speed: 1.3 });
  const bus = shiftTo(pathPoints({ via: [[300, 0], [1500, 0]], speed: 3.5 }), walkA[walkA.length - 1].t + 1000);
  const walkB = shiftTo(pathPoints({ via: [[1500, 0], [1800, 0]], speed: 1.3 }), bus[bus.length - 1].t + 1000);
  const points = [...walkA, ...bus.slice(1), ...walkB.slice(1)];
  const busStart = bus[0].t;
  const busEnd = bus[bus.length - 1].t;
  const steps: Array<[number, number]> = [];
  let count = 0;
  for (let t = T0; t <= points[points.length - 1].t; t += 5000) {
    steps.push([t, Math.round(count)]);
    if (t < busStart || t >= busEnd) count += (110 / 60) * 5;
  }
  pipelineFixture('p05-slow-bus-steps', [{ startedAt: T0, points, motion: { steps } }], { drawing: false });
  pipelineFixture('p06-slow-bus-speed-only', [{ startedAt: T0, points }], { drawing: false });
}

{
  const walkA = pathPoints({ via: [[0, 0], [300, 0]], speed: 1.3 });
  const car = shiftTo(pathPoints({ via: [[300, 0], [1500, 0]], speed: 4 }), walkA[walkA.length - 1].t + 1000);
  pipelineFixture('p07-activity-vehicle', [
    { startedAt: T0, points: [...walkA, ...car.slice(1)], motion: { activity: [[T0, 'walk', 2], [car[0].t, 'vehicle', 2]] } },
  ], { drawing: false });
}

{
  const before = pathPoints({ via: [[0, 0], [200, 0]], speed: 1.4 });
  const pauseAt = before[before.length - 1].t + 500;
  const resumeAt = pauseAt + 10 * 60e3;
  const after = shiftTo(pathPoints({ via: [[1000, 0], [1200, 0]], speed: 1.4 }), resumeAt + 1000);
  pipelineFixture('p08-pause-moved', [{ startedAt: T0, points: [...before, ...after], pauses: [{ start: pauseAt, end: resumeAt }] }]);
}

for (const seed of [1, 2, 3, 4, 5]) {
  pipelineFixture(`p09-square-noise-seed${seed}`, [
    { startedAt: T0, points: pathPoints({ via: [[0, 0], [180, 0], [180, 180], [0, 180], [0, 0]], speed: 1.8, noiseM: 3, seed }) },
  ], seed === 1 ? { probes: [[0, 0], [90, 0], [180, 90], [90, 185], [5, 90]] } : { drawing: seed === 2 });
}

{
  const before = pathPoints({ via: [[0, 0], [207.5, 0]], speed: 1.4 });
  const pauseAt = before[before.length - 1].t + 500;
  const resumeAt = pauseAt + 30e3;
  const after = shiftTo(pathPoints({ via: [[210, 4], [400, 4]], speed: 1.4 }), resumeAt + 1000);
  pipelineFixture('p10-pause-resume-partway', [{ startedAt: T0, points: [...before, ...after], pauses: [{ start: pauseAt, end: resumeAt }] }]);
}

{
  const before = pathPoints({ via: [[0, 0], [103.5, 0]], speed: 1.4 });
  const gapEnd = before[before.length - 1].t + 120e3;
  const after = shiftTo(pathPoints({ via: [[106, 3], [230, 3]], speed: 1.4 }), gapEnd);
  pipelineFixture('p11-long-gap', [{ startedAt: T0, points: [...before, ...after] }]);
}

{
  const walk = pathPoints({ via: [[0, 0], [300, 0]], speed: 1.4 });
  const last = walk[walk.length - 1];
  const standing = shiftTo(pathPoints({ via: [[300, 0], [300, 0.5]], speed: 0.004, noiseM: 2, seed: 4 }), last.t + 1000).map(p => ({ ...p, speed: 0 }));
  pipelineFixture('p12-standing-before-finish', [{ startedAt: T0, points: [...walk, ...standing] }], { drawing: false });
}

for (const drift of [5, 10, 15, 20]) {
  pipelineFixture(`p13-out-and-back-drift${drift}`, days([{ via: [[0, 0], [400, 0], [400, drift], [0, drift]] }]),
    drift === 15 ? { probes: [[0, 0], [200, 7], [400, 15], [410, 0], [200, 30]] } : {});
}

pipelineFixture('p14-slant-and-shaky', [
  ...days([{ via: [[0, 0], [400, 0], [0, 15]] }]),
  ...days([{ via: [[0, 0], [400, 0], [400, 8], [0, 8]], noiseM: 6, acc: 10 }]).map(w => ({ ...w, startedAt: w.startedAt + DAY_MS, points: shiftTo(w.points, w.points[0].t + DAY_MS) })),
]);

pipelineFixture('p15-dead-end-uturn', days([{ via: [[0, 0], [300, 0], [0, 2]], noiseM: 2 }]));
{
  const square: Via = [[0, 0], [180, 0], [180, 180], [0, 180], [0, 0]];
  pipelineFixture('p16-loop-twice', days([{ via: [...square, ...square.slice(1)] }]));
}
for (const gap of [25, 30, 40]) pipelineFixture(`p17-parallel-${gap}m`, days([{ via: [[0, 0], [500, 0]] }, { via: [[0, gap], [500, gap]] }]), { drawing: gap === 25 });
pipelineFixture('p18-crossing', days([{ via: [[0, 0], [400, 0]] }, { via: [[200, -200], [200, 200]] }]), { probes: [[200, 0], [195, 5], [200, -10]] });
pipelineFixture('p19-turn-off', days([{ via: [[0, 0], [400, 0]] }, { via: [[0, 0], [200, 0], [200, 200]] }]));
pipelineFixture('p20-next-day-opposite', days([{ via: [[0, 0], [500, 0]] }, { via: [[500, 15], [0, 15]] }]));
pipelineFixture('p21-three-walks', days([
  { via: [[0, 0], [400, 0]], noiseM: 4 },
  { via: [[0, 0], [400, 0]], noiseM: 4 },
  { via: [[400, 0], [0, 0]], noiseM: 4 },
]));
pipelineFixture('p22-extend-dead-end', days([{ via: [[0, 0], [200, 0]] }, { via: [[100, 0], [400, 0]] }]));
{
  const before = pathPoints({ via: [[0, 0], [300, 0]], speed: 1.4, noiseM: 3, seed: 4 });
  const pauseAt = before[before.length - 1].t + 500;
  const resumeAt = pauseAt + 90e3;
  const after = shiftTo(pathPoints({ via: [[300, 6], [0, 9]], speed: 1.4, noiseM: 3, seed: 5 }), resumeAt + 1000);
  pipelineFixture('p23-pause-at-far-end', [{ startedAt: T0, points: [...before, ...after], pauses: [{ start: pauseAt, end: resumeAt }] }]);
}
pipelineFixture('p24-two-walks-branchy', days([{ via: [[0, 0], [300, 0], [300, 200], [100, 220]] }, { via: [[150, 300], [150, 0], [150, -50]] }]), {
  probes: [[150, 0], [150, 210], [300, 100], [140, 5], [200, 215]],
});
for (const seed of [1, 4, 7]) {
  pipelineFixture(`p25-slant20-seed${seed}`, days([{ via: [[0, 0], [400, 0], [0, 20]], seed }]), { drawing: false });
  pipelineFixture(`p26-block30-seed${seed}`, days([{ via: [[0, 0], [400, 0], [400, 30], [0, 30]], seed }]), { drawing: false });
}

// walk path (v5)
{
  const KNOWN: Via = [[0, -20], [0, 80]];
  const known = (t0: number) => {
    const first = pathPoints({ via: KNOWN, speed: 1.3, noiseM: 0, seed: 9, acc: 5, t0 });
    return { startedAt: t0, points: first, motion: { steps: stepsFor(first) } };
  };
  const t1 = T0 + DAY_MS;
  const back: Via = [[0, 20], [0, 30], [0, 20], [0, 30], [0, 20]];
  const pts = pathPoints({ via: back, speed: 1.3, noiseM: 0, seed: 1, acc: 5, t0: t1 });
  pipelineFixture('p27-known-back-forth-steps', [known(T0), { startedAt: t1, points: pts, motion: { steps: stepsFor(pts) } }]);
  pipelineFixture('p28-known-back-forth-nosteps', [known(T0), { startedAt: t1, points: pts }]);
  pipelineFixture('p29-square-twice-steps', (() => {
    const p = pathPoints({ via: [[0, 0], [60, 0], [60, 60], [0, 60], [0, 0], [60, 0], [60, 60], [0, 60], [0, 0]], speed: 1.3, acc: 5 });
    return [{ startedAt: T0, points: p, motion: { steps: stepsFor(p) } }];
  })());
  pipelineFixture('p30-out-and-back-30m', [{ startedAt: T0, points: pathPoints({ via: [[0, 0], [0, 30], [0, 0]], speed: 1.3, acc: 5 }) }]);
  // jumping fixes, with/without Doppler
  for (const seed of [2, 3]) {
    const first = pathPoints({ via: [[0, -20], [0, 320]], speed: 1.3, seed: 9, acc: 5 });
    const points = pathPoints({ via: [[0, 0], [0, 300]], speed: 1.3, noiseM: 3, seed, acc: 8, t0: t1 }).map((point, i) => {
      const bare = seed % 2 === 0 ? { ...point, speed: null, course: null } : point;
      return (i + seed * 3) % 30 < 4 ? { ...bare, lat: point.lat - 20 / 111195, acc: 15 } : bare;
    });
    pipelineFixture(`p31-jumping-fixes-seed${seed}`, [
      { startedAt: T0, points: first, motion: { steps: stepsFor(first) } },
      { startedAt: t1, points, motion: { steps: stepsFor(points) } },
    ], { drawing: false });
  }
  // standing still with the step counter quiet
  const via: Array<[number, number]> = [];
  for (let k = 0; k < 40; k += 1) via.push([k % 2 === 0 ? 0 : 1, 30]);
  const still = pathPoints({ via, speed: 0.25, noiseM: 6, seed: 4, acc: 12, t0: t1 });
  pipelineFixture('p32-standing-quiet-steps', [known(T0), { startedAt: t1, points: still, motion: { steps: stepsFor(still, 0) } }], { drawing: false });
  // two strokes with a pause in between
  const a = pathPoints({ via: [[0, 0], [0, 60]], speed: 1.3, acc: 5 });
  const b = shiftTo(pathPoints({ via: [[200, 0], [200, 60]], speed: 1.3, acc: 5 }), a[a.length - 1].t + 120e3);
  pipelineFixture('p33-two-strokes', [{ startedAt: T0, points: [...a, ...b], pauses: [{ start: a[a.length - 1].t + 1000, end: b[0].t - 1000 }] }]);
}

// motion: bike with raw Android step counter, counter going down
{
  const run = pathPoints({ via: [[0, 0], [600, 0]], speed: 3.0 });
  const bike = shiftTo(pathPoints({ via: [[600, 0], [2400, 0]], speed: 5.0 }), run[run.length - 1].t + 1000);
  const points = [...run, ...bike.slice(1)];
  const bikeStart = bike[0].t;
  const steps: Array<[number, number]> = [];
  let counter = 1_234_567;
  for (let t = T0; t <= points[points.length - 1].t; t += 5000) {
    steps.push([t, Math.round(counter)]);
    if (t < bikeStart) counter += (165 / 60) * 5;
  }
  pipelineFixture('p34-bike-raw-counter', [{ startedAt: T0, points, motion: { steps } }], { drawing: false });
  const p2 = pathPoints({ via: [[0, 0], [800, 0]], speed: 1.6 });
  const middle = p2[Math.floor(p2.length / 2)].t;
  pipelineFixture('p35-counter-down', [{
    startedAt: T0,
    points: p2,
    motion: { steps: [[T0, 50_000], [middle, 50_600], [middle + 1000, 3], [p2[p2.length - 1].t, 400]] },
  }], { drawing: false });
  // activity: still with confidence, bike, unknown, low-confidence vehicle (ignored)
  const p3 = pathPoints({ via: [[0, 0], [500, 0], [500, 300]], speed: 1.5, noiseM: 2, seed: 11 });
  pipelineFixture('p36-activity-mix', [{
    startedAt: T0,
    points: p3,
    motion: { activity: [[T0 - 60_000, 'walk', 2], [T0 + 60_000, 'vehicle', 0], [T0 + 120_000, 'bike', 1], [T0 + 200_000, 'still', 2], [T0 + 260_000, 'unknown', 2], [T0 + 400_000, 'run', 1]] },
  }], { drawing: false });
}

// standing still indoors (exploreStill.test)
{
  function indoorDrift(seconds: number, t0 = T0, seed = 7): RawPoint[] {
    const random = rng(seed);
    const points: RawPoint[] = [];
    let bx = 0;
    let by = 0;
    for (let s = 0; s < seconds; s += 1) {
      bx = bx * 0.97 + (random() - 0.5) * 3;
      by = by * 0.97 + (random() - 0.5) * 3;
      const hop = s % 9 === 0 ? (random() - 0.5) * 22 : 0;
      const { lat, lng } = plane.toLatLng({ x: bx * 4 + hop, y: by * 4 - hop / 2 });
      points.push({ lat, lng, t: t0 + s * 1000, acc: 12 + random() * 8, alt: null, speed: null, course: null, sacc: null, vacc: null });
    }
    return points;
  }
  function stepLines(from: number, to: number, cadenceAt: (t: number) => number): Array<[number, number]> {
    const lines: Array<[number, number]> = [[from, 0]];
    let n = 0;
    for (let t = from + 5000; t <= to; t += 5000) {
      const added = Math.round((cadenceAt(t) / 60) * 5);
      if (added === 0) continue;
      n += added;
      lines.push([t, n]);
    }
    return lines;
  }
  const drift = indoorDrift(240);
  const end = drift[drift.length - 1].t;
  pipelineFixture('p37-indoor-drift-nosteps-counted', [{ startedAt: T0, points: drift, motion: { steps: stepLines(T0, end, () => 0) } }], { drawing: false });
  pipelineFixture('p38-indoor-drift-no-step-data', [{ startedAt: T0, points: drift }]);
  const walkPart = shiftTo(pathPoints({ via: [[0, 0], [300, 0]], speed: 1.3, acc: 6, noiseM: 3, seed: 3 }), end + 1000);
  const walkStart = walkPart[0].t;
  pipelineFixture('p39-indoor-then-walk', [{
    startedAt: T0,
    points: [...drift, ...walkPart],
    motion: { steps: stepLines(T0, walkPart[walkPart.length - 1].t, t => (t >= walkStart ? 108 : 0)) },
  }]);
}

// odd inputs: unusable points, duplicates/out-of-order times, open pause, points before start, bad accuracy
{
  const base = pathPoints({ via: [[0, 0], [250, 0], [250, 120]], speed: 1.4, noiseM: 2, seed: 21 });
  const odd: RawPoint[] = base.map((p, i) => {
    if (i === 10) return { ...p, lat: 0, lng: 0 };
    if (i === 20) return { ...p, acc: 40 };
    if (i === 30) return { ...p, lat: Number.NaN };
    if (i % 37 === 0) return { ...p, acc: null };
    if (i % 41 === 0) return { ...p, speed: null };
    if (i % 43 === 0) return { ...p, course: null };
    if (i === 60) return { ...p, lat: p.lat + 300 / 111195 }; // a 300 m jump
    return p;
  });
  const dup = odd.slice(50, 55).map(p => ({ ...p })); // duplicates (same t)
  const early = { ...odd[0], t: T0 - 5000 };
  const lastT = odd[odd.length - 1].t;
  pipelineFixture('p40-odd-inputs', [{
    startedAt: T0,
    points: [early, ...odd.slice().reverse(), ...dup],
    pauses: [{ start: T0 + 100_000, end: T0 + 112_000 }, { start: lastT - 20_000, end: null }],
  }]);
}

// ─────────────────────────────── live recording (ExploreRecorder chunked pushes, pause/resume, summary snapshots)
function liveFixture(name: string, points: RawPoint[], chunk: number, opts: { pauses?: RunPause[]; motion?: OnFootMotion } = {}) {
  const graph = new ExploreGraph();
  capture = { samples: [], decisions: [] };
  const live = new ExploreRecorder(graph, { startedAt: T0, motion: opts.motion });
  const snapshots: unknown[] = [];
  const ops: unknown[] = [];
  // pause/resume events are issued between chunks, at their time (a chunk never straddles an event)
  const events: Array<['pause' | 'resume', number]> = [];
  for (const pause of opts.pauses ?? []) {
    events.push(['pause', pause.start]);
    if (pause.end !== null) events.push(['resume', pause.end]);
  }
  events.sort((a, b) => a[1] - b[1]);
  let from = 0;
  const flush = (to: number) => {
    if (to <= from) return;
    ops.push(['push', from, to]);
    live.push(points.slice(from, to));
    const s = live.summary();
    const settled = live.settled();
    snapshots.push({ summary: dumpSummary(s), settledT: fin(settled.t), revision: settled.revision });
    from = to;
  };
  for (let i = 0; i < points.length; i += 1) {
    while (events.length > 0 && events[0][1] <= points[i].t) {
      flush(i);
      const [type, at] = events.shift() as ['pause' | 'resume', number];
      ops.push([type, at]);
      if (type === 'pause') live.pause(at);
      else live.resume(at);
    }
    if (i - from >= chunk) flush(i);
  }
  flush(points.length);
  const finished = live.finish();
  const captured = capture;
  capture = null;
  const atEnd = computeExploreWalk(new ExploreGraph(), { startedAt: T0, points, pauses: opts.pauses, motion: opts.motion });
  fixtures.push({
    name,
    kind: 'live',
    input: { startedAt: T0, points, pauses: opts.pauses, motion: opts.motion },
    ops,
    snapshots,
    finished: dumpSummary(finished),
    atEndEqualsFinished: JSON.stringify(atEnd) === JSON.stringify(finished),
    samples: captured.samples,
    decisions: captured.decisions,
    graph: dumpGraph(graph),
  });
}
liveFixture('l01-live-chunks-7', pathPoints({ via: [[0, 0], [500, 0], [500, 300], [100, 350]], speed: 1.5, noiseM: 3, seed: 5 }), 7);
liveFixture('l02-live-out-and-back-9', pathPoints({ via: [[0, 0], [400, 0], [400, 12], [0, 12]], speed: 1.4, noiseM: 3, seed: 7 }), 9);
{
  const before = pathPoints({ via: [[0, 0], [200, 0]], speed: 1.4, noiseM: 2, seed: 2 });
  const pauseAt = before[before.length - 1].t + 500;
  const resumeAt = pauseAt + 30e3;
  const after = shiftTo(pathPoints({ via: [[200, 3], [400, 3]], speed: 1.4, noiseM: 2, seed: 3 }), resumeAt + 1000);
  liveFixture('l03-live-pause-resume', [...before, ...after], 5, { pauses: [{ start: pauseAt, end: resumeAt }] });
}

// live with motion arriving as it happens (explorePipeline.test 'live recording leaves out a slow bus too')
{
  const walkA = pathPoints({ via: [[0, 0], [300, 0]], speed: 1.5 });
  const bus = shiftTo(pathPoints({ via: [[300, 0], [1300, 0]], speed: 5 }), walkA[walkA.length - 1].t + 1000);
  const walkB = shiftTo(pathPoints({ via: [[1300, 0], [1600, 0]], speed: 1.5 }), bus[bus.length - 1].t + 1000);
  const points = [...walkA, ...bus.slice(1), ...walkB.slice(1)];
  const busStart = bus[0].t;
  const busEnd = bus[bus.length - 1].t;
  const steps: Array<[number, number]> = [];
  const activity: Array<[number, 'walk' | 'vehicle', number]> = [];
  let count = 0;
  let lastStepsAt = -Infinity;
  const motionLens: Array<[number, number]> = [];
  const live = new ExploreRecorder(new ExploreGraph(), { startedAt: T0, motion: { steps, activity } });
  const pioneered: number[] = [];
  for (const point of points) {
    const walking = point.t < busStart || point.t >= busEnd;
    if (walking) count += 1.5 / 0.75;
    if (point.t - lastStepsAt >= 5000 && (steps.length === 0 || steps[steps.length - 1][1] !== Math.floor(count))) {
      steps.push([point.t, Math.floor(count)]);
      lastStepsAt = point.t;
    }
    const kind = walking ? 'walk' : 'vehicle';
    if (activity.length === 0 || activity[activity.length - 1][1] !== kind) activity.push([point.t, kind, 2]);
    motionLens.push([steps.length, activity.length]);
    live.push([point]);
    pioneered.push(live.summary().pioneeredM);
  }
  fixtures.push({
    name: 'l04-live-motion-growing',
    kind: 'liveMotion',
    input: { startedAt: T0, points, motion: { steps, activity } },
    motionLens,
    pioneered,
    finished: dumpSummary(live.finish()),
  });
}

// ─────────────────────────────── graph only (exploreGraph.test walk(): ExplorePoints straight into the net)
function gwalk(
  via: Via,
  { speed = 1.4, t0 = T0, onFoot = true, weak = false, acc = undefined as number | undefined, paced = undefined as boolean | undefined } = {},
): ExplorePoint[] {
  const out: ExplorePoint[] = [];
  let t = t0;
  let carry = 0;
  for (let i = 1; i < via.length; i += 1) {
    const [x0, y0] = via[i - 1];
    const [x1, y1] = via[i];
    const length = Math.hypot(x1 - x0, y1 - y0);
    let d = carry;
    if (i === 1) {
      out.push({ ...plane.toLatLng({ x: x0, y: y0 }), t, onFoot, weak, acc, paced });
      t += 1000;
    }
    while (d + speed <= length + 1e-9) {
      d += speed;
      out.push({ ...plane.toLatLng({ x: x0 + ((x1 - x0) * d) / length, y: y0 + ((y1 - y0) * d) / length }), t, onFoot, weak, acc, paced });
      t += 1000;
    }
    carry = d - length;
  }
  return out;
}
const DAY = exploreDayOf(T0);

function graphFixture(name: string, walks: Array<{ points: ExplorePoint[]; day: number }>, probes?: ReadonlyArray<readonly [number, number]>) {
  const graph = new ExploreGraph();
  const results: unknown[] = [];
  let last: ExploreWalkResult | null = null;
  for (const w of walks) {
    graph.clearDirty();
    last = graph.addWalk(w.points, { day: w.day });
    results.push({ input: { day: w.day, points: w.points }, result: dumpSummary(last) });
  }
  fixtures.push({ name, kind: 'graph', walks: results, graph: dumpGraph(graph), ...dumpDrawing(graph, last), ...(probes ? { probes: dumpProbes(graph, probes) } : {}) });
}
graphFixture('g01-first-walk', [{ points: gwalk([[0, 0], [500, 0]]), day: DAY }]);
graphFixture('g02-other-sidewalk', [{ points: gwalk([[0, 0], [500, 0]]), day: DAY }, { points: gwalk([[0, 6], [500, 6]], { t0: T0 + 3600e3 }), day: DAY + 1 }]);
graphFixture('g03-alley-30m', [{ points: gwalk([[0, 0], [500, 0]]), day: DAY }, { points: gwalk([[0, 30], [500, 30]], { t0: T0 + 3600e3 }), day: DAY }]);
graphFixture('g04-out-and-back', [{ points: gwalk([[0, 0], [400, 0], [0, 3]]), day: DAY }]);
graphFixture('g05-weak', [{ points: gwalk([[0, 0], [300, 0]], { weak: true }), day: DAY }]);
{
  const first = gwalk([[0, 0], [200, 0]]);
  const ride = gwalk([[200, 0], [1200, 0]], { speed: 12, t0: first[first.length - 1].t + 1000, onFoot: false });
  const last = gwalk([[1200, 0], [1400, 0]], { t0: ride[ride.length - 1].t + 1000 });
  graphFixture('g06-ride-gap', [{ points: [...first, ...ride, ...last], day: DAY }]);
}
graphFixture('g07-save-load-continue', [{ points: gwalk([[0, 0], [300, 0], [300, 300]]), day: DAY }, { points: gwalk([[300, 300], [0, 300], [0, 0]], { t0: T0 + 7200e3 }), day: DAY + 1 }]);
graphFixture('g08-drift-band', [
  { points: gwalk([[0, 0], [500, 0]]), day: DAY },
  { points: gwalk([[4, 11], [496, 11]], { t0: T0 + 3600e3 }), day: DAY + 1 },
  { points: gwalk([[0, 15], [500, 15]], { t0: T0 + 7200e3 }), day: DAY + 2 },
], [[250, 0], [250, 13], [0, 15], [500, 20]]);
graphFixture('g09-parallel-25m', [{ points: gwalk([[0, 0], [500, 0]]), day: DAY }, { points: gwalk([[0, 25], [500, 25]], { t0: T0 + 3600e3 }), day: DAY }]);
graphFixture('g10-branch-turn', [{ points: gwalk([[0, 0], [400, 0]]), day: DAY }, { points: gwalk([[0, 0], [200, 0], [200, 200]], { t0: T0 + 3600e3 }), day: DAY + 1 }]);
{
  const via: Array<[number, number]> = [[0, 0]];
  for (let i = 0; i < 5; i += 1) via.push([60, 0], [0, 0]);
  graphFixture('g11-pacing', [{ points: gwalk(via), day: DAY }]);
  graphFixture('g12-pacing-paced', [{ points: gwalk([[0, 0], [30, 0], [24, 0], [30, 0], [20, 0], [60, 0]], { paced: true }), day: DAY }]);
}
graphFixture('g13-step-back', [{ points: gwalk([[0, 0], [100, 0], [92, 0], [200, 0]]), day: DAY }]);
graphFixture('g14-cross-turn-end', [{ points: gwalk([[0, 0], [200, 0], [200, 16], [176, 16]]), day: DAY }]);
graphFixture('g15-diagonal-join', [{ points: gwalk([[0, 0], [300, 0]]), day: DAY }, { points: gwalk([[150, 60], [160, 22], [175, 0], [300, 0]], { t0: T0 + 3600e3 }), day: DAY + 1 }]);
graphFixture('g16-acc-wide', [{ points: gwalk([[0, 0], [400, 0]]), day: DAY }, { points: gwalk([[0, 19], [400, 19]], { t0: T0 + 3600e3, acc: 16 }), day: DAY + 1 }]);
graphFixture('g17-acc-mixed', [
  { points: gwalk([[0, 0], [300, 0], [300, 300]], { acc: 4 }), day: DAY },
  { points: gwalk([[300, 290], [310, 10], [-10, 12], [0, 200]], { t0: T0 + 3600e3, acc: 22 }), day: DAY + 1 },
  { points: gwalk([[0, 200], [0, -5], [150, 30], [150, 150]], { t0: T0 + 7200e3, acc: 9, paced: true }), day: DAY + 2 },
], [[0, 0], [150, 0], [300, 150], [0, 100], [150, 100]]);
graphFixture('g18-grid-walks', [
  { points: gwalk([[0, 0], [200, 0], [200, 200], [0, 200], [0, 0], [100, 0], [100, 200]]), day: DAY },
  { points: gwalk([[0, 100], [200, 100], [200, 0], [100, 0], [100, 100], [0, 100]], { t0: T0 + 3600e3 }), day: DAY + 1 },
  { points: gwalk([[100, 200], [100, -50], [-50, -50], [-50, 100], [0, 100]], { t0: T0 + 7200e3, acc: 12 }), day: DAY + 2 },
], [[100, 100], [0, 0], [200, 200], [-50, 0], [50, 100], [100, 50]]);

// ─────────────────────────────── tiles (exploreTile.test)
{
  const graph = new ExploreGraph();
  computeExploreWalk(graph, { startedAt: T0, points: pathPoints({ via: [[0, 0], [3000, 0], [3000, 1500]], speed: 1.5 }) });
  const tiles = exploreTilesOfGraph(graph);
  const copy = new ExploreGraph();
  for (const data of tiles.values()) loadExploreTile(copy, data);
  const copyNextId = copy.nextId;
  const second = pathPoints({ via: [[3000, 1500], [0, 1500], [0, 0]], speed: 1.5, t0: T0 + DAY_MS });
  const a = computeExploreWalk(graph, { startedAt: T0 + DAY_MS, points: second });
  const b = computeExploreWalk(copy, { startedAt: T0 + DAY_MS, points: second });
  fixtures.push({
    name: 't01-tiles-roundtrip',
    kind: 'tilesRoundtrip',
    first: { startedAt: T0, points: pathPoints({ via: [[0, 0], [3000, 0], [3000, 1500]], speed: 1.5 }) },
    second: { startedAt: T0 + DAY_MS, points: second },
    tilesAfterFirst: [...tiles.entries()],
    copyNextId,
    resultFull: dumpSummary(a),
    resultCopy: dumpSummary(b),
    graphFull: dumpGraph(graph),
    graphCopy: dumpGraph(copy),
    tilesFinal: [...exploreTilesOfGraph(graph).entries()],
  });
}
{
  const full = new ExploreGraph();
  const firstPoints = pathPoints({ via: [[0, 0], [6000, 0]], speed: 1.5, noiseM: 3 });
  computeExploreWalk(full, { startedAt: T0, points: firstPoints });
  const tiles = exploreTilesOfGraph(full);
  const t1 = T0 + DAY_MS;
  const next = pathPoints({ via: [[2000, 5], [2900, 5], [2900, 15], [2000, 15]], speed: 1.4, noiseM: 3, seed: 8, t0: t1 });
  const aroundList = exploreTilesAround(next);
  const around = new Set(aroundList.map(exploreTileKey));
  const partial = new ExploreGraph(full.nextId);
  for (const [key, data] of tiles) if (around.has(key)) loadExploreTile(partial, data);
  const a = computeExploreWalk(full, { startedAt: t1, points: next });
  const b = computeExploreWalk(partial, { startedAt: t1, points: next });
  fixtures.push({
    name: 't02-tiles-partial',
    kind: 'tilesPartial',
    first: { startedAt: T0, points: firstPoints },
    next: { startedAt: t1, points: next },
    tilesAfterFirst: [...tiles.entries()],
    around: aroundList,
    partialNodeCount: partial.nodeCount,
    resultFull: dumpSummary(a),
    resultPartial: dumpSummary(b),
    changedPartial: [...exploreTilesOfGraph(partial, around).entries()],
    changedFull: [...exploreTilesOfGraph(full, around).entries()],
    linesOverview: dumpLines(exploreLines(full.edges(), { simplifyM: 25, minLengthM: 50, smooth: true })),
  });
}
{
  // tile math probes
  const pts: Array<[number, number]> = [
    [37.5283, 126.9326], [37.5665, 126.978], [35.1796, 129.0756], [33.4996, 126.5312], [0, 0], [-33.8688, 151.2093],
    [51.5074, -0.1278], [85.2, 179.9999], [-89.9, -180], [40.7128, -74.006], [37.0, 127.0], [37.49999, 126.99999],
  ];
  const tileOf = pts.map(([lat, lng]) => [lat, lng, exploreTileOf(lat, lng), exploreTileOf(lat, lng, 11)]);
  const bounds = pts.map(([lat, lng]) => {
    const tile = exploreTileOf(lat, lng);
    return [tile, exploreTileBounds(tile), exploreTileBounds(exploreTileOf(lat, lng, 11), 11)];
  });
  const around = [
    exploreTilesAround(pts.slice(0, 4).map(([lat, lng]) => ({ lat, lng })), 60),
    exploreTilesAround(pathPoints({ via: [[0, 0], [2500, 0], [2500, 2500]], speed: 25 }), 120),
    exploreTilesAround([{ lat: Number.NaN, lng: 1 }, { lat: 37.5, lng: 127 }], 2000),
  ];
  const toLatLng = (xy: ReadonlyArray<readonly [number, number]>) => xy.map(([x, y]) => {
    const { lat, lng } = plane.toLatLng({ x, y });
    return [lat, lng] as [number, number];
  });
  const shapes = [
    toLatLng(Array.from({ length: 12 }, (_, i) => [i * 8, i % 2 === 0 ? -4 : 4] as const)),
    toLatLng(Array.from({ length: 6 }, (_, i) => [i * 8, 0] as const)),
    toLatLng([[0, 0], [8, 0], [16, 0], [24, 0], [24, 8], [24, 16], [24, 24]]),
    toLatLng([[0, 0], [8, 1]]),
  ];
  const smooth = shapes.map(s => [smoothExploreCoords(s), smoothExploreCoords(s, 1), smoothExploreCoords(s, 0), smoothExploreCoords(s, 5)]);
  fixtures.push({ name: 't03-tile-math', kind: 'tileMath', tileOf, bounds, around, shapes, smooth });
}

// ─────────────────────────────── write
const outDir = process.argv[2] ?? 'golden';
fs.mkdirSync(outDir, { recursive: true });
for (const f of fs.readdirSync(outDir)) if (f.endsWith('.json')) fs.unlinkSync(path.join(outDir, f));
let bytes = 0;
for (const fixture of fixtures) {
  const text = JSON.stringify(fixture);
  bytes += text.length;
  fs.writeFileSync(path.join(outDir, `${fixture.name}.json`), text);
}
fs.writeFileSync(path.join(outDir, 'index.json'), JSON.stringify(fixtures.map(f => ({ name: f.name, kind: f.kind }))));
console.log(`wrote ${fixtures.length} fixtures (${(bytes / 1e6).toFixed(1)} MB) to ${outDir}`);
