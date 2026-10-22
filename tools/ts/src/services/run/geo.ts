// 러닝 기록의 위경도 계산.
// 거리는 구면 하버사인. 짧은 거리의 평면 계산(점 줄이기·제자리 흔들림·경로 그림)은 기준점 둘레의 등장방형(equirectangular)
// 근사를 쓴다: 경도 차에 cos(위도)를 곱해 동서 m로, 위도 차를 남북 m로 본다. 수십 km 안에서는 오차가 점 줄이기 기준(2m)보다
// 훨씬 작다. 날짜 변경선(경도 ±180)을 넘는 경로는 평면 계산에서 다루지 않는다(하버사인 거리는 맞다).

export type LatLng = { lat: number; lng: number };
/** 기준점에서 동쪽(x)·북쪽(y)으로 몇 m인지. */
export type LocalXY = { x: number; y: number };

/** 지구 평균 반지름(m, IUGG). */
export const EARTH_RADIUS_M = 6371008.8;
const RAD = Math.PI / 180;
/** 위도 1도의 길이(m). 경도 1도는 여기에 cos(위도)를 곱한다. */
export const METERS_PER_DEGREE = EARTH_RADIUS_M * RAD;

/** 두 점 사이 대원 거리(m). */
export function haversineM(a: LatLng, b: LatLng): number {
  const sinLat = Math.sin(((b.lat - a.lat) * RAD) / 2);
  const sinLng = Math.sin(((b.lng - a.lng) * RAD) / 2);
  const h = sinLat * sinLat + Math.cos(a.lat * RAD) * Math.cos(b.lat * RAD) * sinLng * sinLng;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** 그 위도에서 경도 1도의 길이(m). */
export function metersPerDegreeLng(lat: number): number {
  return METERS_PER_DEGREE * Math.cos(lat * RAD);
}

export type LocalProjection = {
  toXY(p: LatLng): LocalXY;
  toLatLng(xy: LocalXY): LatLng;
};

/**
 * origin 둘레의 평면 근사. refLat은 경도 보정(cos)에 쓸 위도 — 넓은 경로는 가운데 위도를 주면 양 끝 오차가 반으로 준다.
 */
export function localProjection(origin: LatLng, refLat: number = origin.lat): LocalProjection {
  const kx = metersPerDegreeLng(refLat);
  const ky = METERS_PER_DEGREE;
  return {
    toXY: p => ({ x: (p.lng - origin.lng) * kx, y: (p.lat - origin.lat) * ky }),
    toLatLng: xy => ({ lat: origin.lat + xy.y / ky, lng: origin.lng + (kx === 0 ? 0 : xy.x / kx) }),
  };
}

/** a→b 선분 위 f(0~1) 지점. 몇 m~몇백 m 선분이라 위경도를 그대로 잇는다. */
export function interpolateLatLng(a: LatLng, b: LatLng, f: number): LatLng {
  return { lat: a.lat + (b.lat - a.lat) * f, lng: a.lng + (b.lng - a.lng) * f };
}
