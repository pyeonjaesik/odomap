// 개척 기록 하나의 계산 순서 — 순수 계산. 앱과 서버가 같은 파일을 쓴다:
//   앱  src/services/run/explore/explorePipeline.ts             — 기록 중 어림(ExploreRecorder), 기록 직후 미리보기
//   서버 src/services/workouts/track/explore/explorePipeline.ts — 올라온 기록으로 확정(computeExploreWalk)
// 두 파일은 글자 하나까지 같아야 한다(scripts/check-shared-run-code.sh). 부르는 파일(../runDistance, ../runTrack, ./exploreGraph, ./onFoot)도
// 두 저장소에 같은 이름·같은 내용으로 있다(앱 src/services/run ↔ 서버 src/services/workouts/track).
//
//  원본 점 → (일시정지 밖·쓸 수 있는 점만) → PathSmoother(칼만 필터·스무딩, 거리 계산과 같은 것) → OnFootClassifier(걷기·달리기만)
//          → ExploreWalk(걸은 길 그물에 넣기)
// 다듬기는 거리 계산과 같은 필터지만 튄 점으로 버리는 속도를 탈것 속도 위로 올린다(EXPLORE_MAX_SPEED_MPS) — 탈것 구간도 위치를 따라가야
// '탈것 8분'처럼 가려낼 수 있다(걷기 기록기는 5m/s 넘는 점을 버려 버스가 막혀 느릴 때만 남았다).
// 다듬은 위치마다 원본 점의 위치 오차를 같이 넘긴다(v4 — 그물이 오차에 따라 아는 길에 붙는 거리를 넓힌다. 다듬기는 원본 점과 같은 시각으로 낸다).
// 걸음으로 걷는 것을 확인한 걸음(paced — 가리기가 정한다)도 넘긴다(v5 — 그물이 짧은 왕복도 다시 지난 것으로 센다).
// 같은 점·일시정지·걸음을 같은 순서로 넣으면 늘 같은 답이다(기록 중 확정된 몫 = 끝낸 뒤 계산의 앞부분).

import { DopplerCheck, PathSmoother, type SmoothedSample } from '../runDistance';
import { RUN_FILTER, type RawPoint, type RunPause } from '../runTrack';
import { EXPLORE_RULES, ExploreGraph, ExploreWalk, exploreDayOf, type ExploreWalkResult } from './exploreGraph';
import { ON_FOOT_RULES, OnFootClassifier, type OnFootMotion, type OnFootStats } from './onFoot';

/** 다듬기에서 튄 점으로 버리는 속도(m/s, 약 290km/h — 기차까지 따라간다) */
export const EXPLORE_MAX_SPEED_MPS = 80;

/** 계산 판(그물·가리기 규칙). 서버가 저장한 결과와 다르면 다시 쌓는다. */
export const EXPLORE_ALGORITHM = `g${EXPLORE_RULES.version}.f${ON_FOOT_RULES.version}`;

export type ExploreWalkSummary = ExploreWalkResult & OnFootStats;

function usable(point: RawPoint): boolean {
  const { lat, lng, t } = point;
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || !Number.isFinite(t)) return false;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return false;
  if (lat === 0 && lng === 0) return false;
  const acc = point.acc;
  return !(typeof acc === 'number' && Number.isFinite(acc) && acc > RUN_FILTER.maxAccuracyM);
}

/**
 * 기록 하나를 점이 오는 대로 그물에 넣는다. 기록 중(앱)은 push·pause·resume 뒤 summary()로 확정된 몫을 읽고, 끝낼 때 finish().
 * graph는 이 기록이 바꾼다(기록 중 앱은 둘레 조각으로 만든 사본을 쓰고, 끝난 뒤 서버 답으로 바꾼다).
 */
export class ExploreRecorder {
  private readonly doppler = new DopplerCheck(RUN_FILTER);
  private piece: PathSmoother;
  private readonly classifier: OnFootClassifier;
  private readonly walk: ExploreWalk;
  private readonly pauses: RunPause[] = [];
  private lastT = -Infinity;
  private finished: ExploreWalkSummary | null = null;
  /** 다듬기에 넣은 원본 점의 [시각, 위치 오차] — 다듬은 위치가 같은 시각으로 나오면 그 오차를 붙인다(버린 점은 지나친다) */
  private readonly accs: Array<[number, number | null]> = [];
  private accHead = 0;

  constructor(
    graph: ExploreGraph,
    private readonly options: { startedAt: number; motion?: OnFootMotion },
  ) {
    this.walk = graph.beginWalk({ day: exploreDayOf(options.startedAt) });
    this.classifier = new OnFootClassifier(options.motion ?? {}, decision => {
      this.walk.push({
        t: decision.t,
        lat: decision.lat,
        lng: decision.lng,
        onFoot: decision.onFoot,
        weak: decision.weak,
        restart: decision.restart,
        acc: decision.acc ?? null,
        paced: decision.paced,
      });
    });
    this.piece = this.newPiece();
  }

  /** 원본 점(시각순). 시작 전·일시정지 동안·같거나 이른 점은 버린다. */
  push(points: ReadonlyArray<RawPoint>): void {
    if (this.finished) return;
    for (const point of points) {
      if (!usable(point) || point.t <= this.lastT || point.t < this.options.startedAt) continue;
      this.lastT = point.t;
      const open = this.pauses[this.pauses.length - 1];
      if (open !== undefined && open.end === null && point.t >= open.start) continue;
      const acc = point.acc;
      this.accs.push([point.t, typeof acc === 'number' && Number.isFinite(acc) && acc > 0 ? acc : null]);
      this.piece.add(point);
    }
  }

  /** 그물에 넣은(확정한) 마지막 걸음의 시각과 그 결과가 바뀐 수 — 기록 중 그림이 여기까지는 그물 선으로 그린다 */
  settled(): { t: number; revision: number } {
    return { t: this.walk.settled().t, revision: this.walk.revision };
  }

  /** 이 기록의 그물 걸음(기록 중 그림 — 이번에 지난 이음과 지난 수) */
  get walkState(): ExploreWalk {
    return this.walk;
  }

  pause(atMs: number): void {
    const last = this.pauses[this.pauses.length - 1];
    if ((last !== undefined && last.end === null) || !Number.isFinite(atMs)) return;
    this.pauses.push({ start: atMs, end: null });
  }

  /** 다시 시작: 멈춘 사이는 잇지 않는다(지난 조각을 확정하고 새 조각). */
  resume(atMs: number): void {
    const last = this.pauses[this.pauses.length - 1];
    if (last === undefined || last.end !== null || !Number.isFinite(atMs)) return;
    last.end = Math.max(atMs, last.start);
    this.piece.close();
    this.piece = this.newPiece();
  }

  /** 지금까지 확정된 몫(다듬기·가리기가 늦게 확정하는 끝의 몇십 초는 빠진다). */
  summary(): ExploreWalkSummary {
    if (this.finished) return this.finished;
    return { ...this.walk.result(), ...this.classifier.stats };
  }

  /** 남은 점을 모두 확정하고 끝낸다. */
  finish(): ExploreWalkSummary {
    if (this.finished) return this.finished;
    this.piece.close();
    this.classifier.flush();
    const result = this.walk.end();
    this.finished = { ...result, ...this.classifier.stats };
    return this.finished;
  }

  private newPiece(): PathSmoother {
    return new PathSmoother(RUN_FILTER, EXPLORE_MAX_SPEED_MPS, this.doppler, (sample: SmoothedSample) => {
      this.classifier.push({
        t: sample.t,
        lat: sample.lat,
        lng: sample.lng,
        speed: sample.speed,
        weak: sample.weak,
        restart: sample.restart,
        acc: this.accAt(sample.t),
      });
    });
  }

  /** 다듬은 위치(시각 t)의 원본 점 위치 오차 — 그보다 이른 것(다듬기가 버린 점)은 지나친다 */
  private accAt(t: number): number | null {
    const accs = this.accs;
    while (this.accHead < accs.length && accs[this.accHead][0] < t) this.accHead += 1;
    let acc: number | null = null;
    if (this.accHead < accs.length && accs[this.accHead][0] === t) {
      acc = accs[this.accHead][1];
      this.accHead += 1;
    }
    // 앞부분이 쌓이지 않게 가끔 비운다
    if (this.accHead > 256 && this.accHead * 2 > accs.length) {
      accs.splice(0, this.accHead);
      this.accHead = 0;
    }
    return acc;
  }
}

/** 끝난 기록 하나를 한 번에(서버 확정, 앱 미리보기). pauses는 ms epoch(끝나지 않은 일시정지는 end null). */
export function computeExploreWalk(
  graph: ExploreGraph,
  input: { startedAt: number; points: ReadonlyArray<RawPoint>; pauses?: ReadonlyArray<RunPause>; motion?: OnFootMotion },
): ExploreWalkSummary {
  const recorder = new ExploreRecorder(graph, { startedAt: input.startedAt, motion: input.motion });
  const pauses = (input.pauses ?? []).slice().sort((a, b) => a.start - b.start);
  let next = 0;
  let resumeAt: number | null = null;
  const points = input.points.slice().sort((a, b) => a.t - b.t);
  for (const point of points) {
    if (resumeAt !== null && point.t >= resumeAt) {
      recorder.resume(resumeAt);
      resumeAt = null;
    }
    while (resumeAt === null && next < pauses.length && pauses[next].start <= point.t) {
      const pause = pauses[next];
      next += 1;
      recorder.pause(pause.start);
      if (pause.end === null) break;
      if (pause.end <= point.t) recorder.resume(pause.end);
      else resumeAt = pause.end;
    }
    recorder.push([point]);
  }
  return recorder.finish();
}
