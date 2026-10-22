#!/usr/bin/env python3
"""시연용 주행 경로 만들기.

OSRM(공개 데모 서버)으로 실제 도로를 따라가는 경로를 받고, 1초 간격 GPS 기록처럼 만든다:
신호 대기 정지, 가감속, GPS 흔들림(AR(1)), 터널 끊김, 출발 D·주차 P 기어 이벤트.

출력(드라이브마다): <out>/<id>.jsonl, <out>/<id>.gpx
  {"k":"meta","id","title","start","distance_m"}
  {"k":"gear","t":ms,"g":"D"|"P"}
  {"k":"loc","t":ms,"lat","lng","acc","spd","brg"}

같은 씨앗이면 같은 결과(OSRM 응답은 routes_cache/에 저장).
사용: python3 -I gen_routes.py [--out DIR]
"""
import argparse
import json
import math
import os
import random
import subprocess
import sys
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "routes_cache")
KST = timezone(timedelta(hours=9))
R = 6371008.8

# 장소 (경도, 위도)
HAPJEONG = (126.9139, 37.5495)      # 합정역 근처 (집)
YEOKSAM = (127.0366, 37.5006)       # 역삼역 근처 (회사)
HANNAM_BR = (127.0128, 37.5265)     # 한남대교
SEONGSU_BR = (127.0346, 37.5367)    # 성수대교
NAMI = (127.5258, 37.7906)          # 가평 남이섬 선착장
GANGNEUNG = (128.8761, 37.7519)     # 강릉 경포
JUMUNJIN = (128.8215, 37.8925)      # 주문진항
NAKSAN = (128.6275, 38.1245)        # 양양 낙산
SOKCHO = (128.5918, 38.2070)        # 속초항
NORTH, SOUTH = (350, 60), (170, 60)  # 한강 다리 경유지 진행 방향 (방위각, 허용 범위)


def drives():
    d = []
    # 출퇴근: 같은 길 반복(굵어짐) + 하루는 다른 다리로 우회(처음 달린 길)
    day0 = datetime(2026, 10, 26, tzinfo=KST)  # 월
    for i in range(5):
        day = day0 + timedelta(days=i)
        via = [SEONGSU_BR] if i == 2 else [HANNAM_BR]
        d.append(dict(id=f"commute-{i + 1}-am", title=f"출근 {day:%m/%d}",
                      pts=[HAPJEONG, *via, YEOKSAM], bearings=[None, SOUTH, None],
                      start=day.replace(hour=8, minute=12 + i), urban=True))
        d.append(dict(id=f"commute-{i + 1}-pm", title=f"퇴근 {day:%m/%d}",
                      pts=[YEOKSAM, *via, HAPJEONG], bearings=[None, NORTH, None],
                      start=day.replace(hour=19, minute=3 + 2 * i), urban=True))
    sat = datetime(2026, 10, 31, 9, 40, tzinfo=KST)
    d.append(dict(id="weekend-gapyeong", title="주말 드라이브 · 가평", pts=[HAPJEONG, NAMI], start=sat,
                  urban=False, tunnels=[0.55, 0.78]))
    d.append(dict(id="weekend-gapyeong-back", title="가평에서 돌아오는 길", pts=[NAMI, HAPJEONG],
                  start=sat.replace(hour=17, minute=20), urban=False, tunnels=[0.3]))
    trip = datetime(2026, 11, 7, 7, 30, tzinfo=KST)
    d.append(dict(id="trip-gangneung", title="강릉 여행", pts=[HAPJEONG, GANGNEUNG], start=trip,
                  urban=False, tunnels=[0.62, 0.71, 0.83, 0.9]))
    d.append(dict(id="trip-coast", title="동해안 해안도로", pts=[GANGNEUNG, JUMUNJIN, NAKSAN, SOKCHO],
                  start=trip.replace(hour=14, minute=10), urban=False, tunnels=[]))
    return d


def osrm(pts, bearings=None):
    os.makedirs(CACHE, exist_ok=True)
    key = "_".join(f"{x:.4f},{y:.4f}" for x, y in pts)
    if bearings:
        key += "_b" + "-".join("x" if b is None else f"{b[0]}r{b[1]}" for b in bearings)
    path = os.path.join(CACHE, key.replace(",", "-") + ".json")
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    coords = ";".join(f"{x},{y}" for x, y in pts)
    url = (f"https://router.project-osrm.org/route/v1/driving/{coords}"
           "?overview=full&geometries=geojson&annotations=distance,duration")
    if bearings:
        # 다리 경유지는 진행 방향 차로에 붙인다(반대 차로에 붙으면 U턴 경로가 나온다)
        url += "&bearings=" + ";".join("" if b is None else f"{b[0]},{b[1]}" for b in bearings)
    # python.org 빌드는 시스템 인증서를 못 쓰는 경우가 있어 curl로 받는다
    data = json.loads(subprocess.run(["curl", "-sSf", "--max-time", "60", url],
                                     check=True, capture_output=True).stdout)
    if data.get("code") != "Ok":
        raise RuntimeError(f"OSRM {data.get('code')}: {key}")
    route = data["routes"][0]
    out = {
        "coords": route["geometry"]["coordinates"],
        "dist": [x for leg in route["legs"] for x in leg["annotation"]["distance"]],
        "dur": [x for leg in route["legs"] for x in leg["annotation"]["duration"]],
    }
    with open(path, "w") as f:
        json.dump(out, f)
    return out


def hav(a, b):
    lng1, lat1 = map(math.radians, a)
    lng2, lat2 = map(math.radians, b)
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * R * math.asin(min(1, math.sqrt(h)))


def bearing(a, b):
    lng1, lat1 = map(math.radians, a)
    lng2, lat2 = map(math.radians, b)
    y = math.sin(lng2 - lng1) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(lng2 - lng1)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def offset(p, north_m, east_m):
    lng, lat = p
    return (lng + math.degrees(east_m / (R * math.cos(math.radians(lat)))), lat + math.degrees(north_m / R))


def simulate(drive, rnd):
    route = osrm(drive["pts"], drive.get("bearings"))
    coords = route["coords"]
    seg_len = [hav(coords[i], coords[i + 1]) for i in range(len(coords) - 1)]
    # 구간 목표 속력: OSRM 자유 흐름 속력 × 교통(도심 0.6~0.8, 교외 0.85~1.0)
    traffic = (0.6, 0.8) if drive["urban"] else (0.85, 1.0)
    seg_v = []
    for d, t in zip(route["dist"], route["dur"]):
        v = d / t if t > 0 else 8.0
        seg_v.append(max(3.0, min(33.0, v * rnd.uniform(*traffic))))
    cum = [0.0]
    for L in seg_len:
        cum.append(cum[-1] + L)
    total = cum[-1]

    # 신호 대기: 느린(도심) 구간에서 450~1100m마다 15~70초
    stops = []
    s = rnd.uniform(300, 700)
    while s < total - 200:
        i = max(0, min(len(seg_v) - 1, next(k for k in range(len(cum) - 1) if cum[k + 1] >= s)))
        if seg_v[i] < 17:
            stops.append([s, rnd.uniform(15, 70) if rnd.random() < 0.7 else 0.0])
        s += rnd.uniform(450, 1100) if seg_v[i] < 17 else rnd.uniform(3000, 8000)
    stops = [st for st in stops if st[1] > 0]

    def seg_at(dist):
        lo, hi = 0, len(cum) - 2
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if cum[mid] <= dist:
                lo = mid
            else:
                hi = mid - 1
        return lo

    def pos_at(dist):
        i = seg_at(dist)
        L = seg_len[i] or 1e-9
        f = (dist - cum[i]) / L
        a, b = coords[i], coords[i + 1]
        return (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f), i

    t0 = int(drive["start"].timestamp() * 1000)
    out = [{"k": "meta", "id": drive["id"], "title": drive["title"], "start": drive["start"].isoformat(),
            "distance_m": round(total)}, {"k": "gear", "t": t0, "g": "D"}]
    tunnels = [(f * total, f * total + rnd.uniform(500, 1600)) for f in drive.get("tunnels", [])]

    ACC, DEC = 1.6, 2.2
    dist, v, t = 0.0, 0.0, t0 + 4000  # 시동 뒤 4초 있다 출발
    wait_until = None
    nxt = 0
    nN = nE = 0.0
    gps_sigma = 4.5 if drive["urban"] else 3.0
    last_p = None
    while dist < total - 0.5:
        if wait_until is not None:
            if t >= wait_until:
                wait_until = None
        else:
            i = seg_at(dist)
            target = seg_v[i]
            # 다음 정지점·끝에서 멈출 수 있는 속력
            if nxt < len(stops):
                target = min(target, math.sqrt(2 * DEC * max(0.0, stops[nxt][0] - dist)))
            target = min(target, math.sqrt(2 * DEC * max(0.0, total - dist)) + 0.3)
            v = min(target, v + ACC) if target > v else max(target, v - DEC * 1.2)
            dist = min(total, dist + v)
            if nxt < len(stops) and dist >= stops[nxt][0] - 1.0:
                dist = max(dist, stops[nxt][0])
                v = 0.0
                wait_until = t + int(stops[nxt][1] * 1000)
                nxt += 1
        t += 1000
        p, i = pos_at(dist)
        if any(a <= dist <= b for a, b in tunnels):
            continue
        # GPS 흔들림: AR(1), 멈춰 있을 때도 조금 떠돈다
        k = 0.85
        nN = k * nN + math.sqrt(1 - k * k) * rnd.gauss(0, gps_sigma)
        nE = k * nE + math.sqrt(1 - k * k) * rnd.gauss(0, gps_sigma)
        q = offset(p, nN, nE)
        brg = bearing(coords[i], coords[i + 1])
        acc = round(max(3.0, abs(rnd.gauss(gps_sigma * 1.2, 1.5))), 1)
        out.append({"k": "loc", "t": t, "lat": round(q[1], 7), "lng": round(q[0], 7), "acc": acc,
                    "spd": round(max(0.0, v + rnd.gauss(0, 0.2)), 2), "brg": round(brg, 1)})
        last_p = q
    out.append({"k": "gear", "t": t + 3000, "g": "P"})
    return out, total, last_p


def write_gpx(path, title, rows):
    with open(path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="odomap-demo" '
                'xmlns="http://www.topografix.com/GPX/1/1">\n')
        f.write(f"<trk><name>{title}</name><trkseg>\n")
        for r in rows:
            if r["k"] != "loc":
                continue
            ts = datetime.fromtimestamp(r["t"] / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            f.write(f'<trkpt lat="{r["lat"]}" lon="{r["lng"]}"><time>{ts}</time></trkpt>\n')
        f.write("</trkseg></trk></gpx>\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(HERE, "out"))
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    index = []
    for n, drive in enumerate(drives()):
        rnd = random.Random(20261022 + n)
        rows, total, _ = simulate(drive, rnd)
        with open(os.path.join(args.out, drive["id"] + ".jsonl"), "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n")
        write_gpx(os.path.join(args.out, drive["id"] + ".gpx"), drive["title"], rows)
        locs = [r for r in rows if r["k"] == "loc"]
        mins = (locs[-1]["t"] - locs[0]["t"]) / 60000
        index.append({"id": drive["id"], "title": drive["title"], "start": drive["start"].isoformat(),
                      "distance_m": round(total), "points": len(locs), "minutes": round(mins, 1)})
        print(f"{drive['id']:24s} {total / 1000:7.1f} km {mins:6.1f} min {len(locs):6d} pts", file=sys.stderr)
    with open(os.path.join(args.out, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
