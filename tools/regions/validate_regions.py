#!/usr/bin/env python3
"""out/regions.json 검증 + 시연 주행의 '새 지역 첫 방문' 순서 만들기. (stdlib 만 사용 — 앱 판정 로직의 기준 구현)

1) 알려진 지점 point-in-polygon 테스트
2) tools/routes/out/*.jsonl 주행을 폴리곤으로 판정 -> 지역 진입 순서
   - 어느 폴리곤에도 안 들어가면(해안선 단순화 오차 등) 직전 지역 유지
   - 새 지역은 30초 이상 머물러야 '진입' 확정(경계 깜빡임 무시)
   - 주행 시작 시각 순으로 누적해 처음 방문한 지역에 firstVisit=true
출력: out/demo-region-sequence.json
사용: python3 -I validate_regions.py
"""
import glob
import json
import os
import sys
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROUTES = os.path.join(HERE, "..", "routes", "out")
KST = timezone(timedelta(hours=9))
DWELL_MS = 30_000

TESTS = [
    ("서울 합정역", 37.5495, 126.9139, "마포구"),
    ("역삼역", 37.5006, 127.0366, "강남구"),
    # 주의: 37.7906,127.5258 은 남이섬(섬 쪽)이며 법정 경계상 강원 춘천시 남산면 방하리다(원본 경계도 동일).
    #       가평 쪽 가평나루 선착장/가평읍은 가평군.
    ("남이섬 (routes NAMI 좌표)", 37.7906, 127.5258, "춘천시"),
    ("가평역", 37.8145, 127.5106, "가평군"),
    ("강릉 경포", 37.7519, 128.8761, "강릉시"),
    ("주문진항", 37.8925, 128.8215, "강릉시"),
    ("양양 낙산", 38.1245, 128.6275, "양양군"),
    ("속초항", 38.2070, 128.5918, "속초시"),
    ("제주공항", 33.5104, 126.4914, "제주시"),
    ("수원 화성행궁", 37.2818, 127.0135, "수원시"),
    ("서귀포 중문", 33.2496, 126.4120, "서귀포시"),
    ("독도 동도", 37.2408, 131.8696, "울릉군"),
    ("광주 5·18 민주광장", 35.1468, 126.9197, "동구"),
    ("인천공항", 37.4602, 126.4407, "영종구"),
]


def ring_contains(ring, lat, lng):
    """even-odd ray casting. ring = [lat0,lng0,lat1,lng1,...] 정수 마이크로도, 닫는 점 생략."""
    inside = False
    n = len(ring) // 2
    j = n - 1
    for i in range(n):
        yi, xi = ring[2 * i], ring[2 * i + 1]
        yj, xj = ring[2 * j], ring[2 * j + 1]
        if (yi > lat) != (yj > lat):
            x = xi + (lat - yi) * (xj - xi) / (yj - yi)
            if lng < x:
                inside = not inside
        j = i
    return inside


class Index:
    def __init__(self, path):
        with open(path, encoding="utf-8") as f:
            self.data = json.load(f)
        self.regions = self.data["regions"]

    def locate(self, lat, lng):
        la, ln = round(lat * 1e6), round(lng * 1e6)
        for r in self.regions:
            b = r["bbox"]
            if not (b[0] <= la <= b[2] and b[1] <= ln <= b[3]):
                continue
            for poly in r["polygons"]:
                if not ring_contains(poly[0], la, ln):
                    continue
                if any(ring_contains(h, la, ln) for h in poly[1:]):
                    continue
                return r
        return None


def read_drive(path):
    meta, locs = None, []
    with open(path, encoding="utf-8") as f:
        for line in f:
            o = json.loads(line)
            if o.get("k") == "meta":
                meta = o
            elif o.get("k") == "loc":
                locs.append(o)
    return meta, locs


def sequence(idx, locs):
    """판정 -> 30초 체류 확정 -> 진입 목록."""
    entries = []
    current = None  # 확정된 지역 코드
    cand, cand_t, cand_pt = None, None, None
    outside = 0
    for o in locs:
        r = idx.locate(o["lat"], o["lng"])
        if r is None:
            outside += 1
            continue  # 직전 상태 유지
        code = r["code"]
        if code == current:
            cand = None
            continue
        if code != cand:
            cand, cand_t, cand_pt = code, o["t"], o
        is_start = current is None
        if is_start or o["t"] - cand_t >= DWELL_MS:
            rr = next(x for x in idx.regions if x["code"] == code)
            entries.append(dict(code=code, name=rr["name"], label=rr["label"], full=rr["full"], t=cand_t,
                                time=datetime.fromtimestamp(cand_t / 1000, KST).strftime("%H:%M:%S"),
                                lat=cand_pt["lat"], lng=cand_pt["lng"], start=is_start))
            current, cand = code, None
    return entries, outside


def main():
    idx = Index(os.path.join(HERE, "out", "regions.json"))
    ok = True
    print("== 지점 테스트")
    results = []
    for label, lat, lng, want in TESTS:
        r = idx.locate(lat, lng)
        got = r["name"] if r else None
        passed = got == want
        ok &= passed
        results.append(dict(place=label, lat=lat, lng=lng, expect=want, got=r["full"] if r else None, ok=passed))
        print(f"  {'OK ' if passed else 'FAIL'} {label:12s} -> {r['full'] if r else '(없음)'}")

    drives = []
    for p in glob.glob(os.path.join(ROUTES, "*.jsonl")):
        meta, locs = read_drive(p)
        drives.append((meta["start"], meta, locs))
    drives.sort(key=lambda d: d[0])
    seen = set()
    out = []
    print("== 주행별 지역 진입 순서 (* = 첫 방문)")
    for _, meta, locs in drives:
        entries, outside = sequence(idx, locs)
        for e in entries:
            e["firstVisit"] = e["code"] not in seen
            seen.add(e["code"])
        out.append(dict(id=meta["id"], title=meta["title"], start=meta["start"], points=len(locs),
                        unclassifiedPoints=outside, regions=entries))
        print(f"  {meta['id']:24s} {meta['title']}: " +
              " -> ".join(("*" if e["firstVisit"] else "") + e["label"] for e in entries) +
              (f"   (미판정 {outside}점)" if outside else ""))
    new_order = [dict(code=e["code"], label=e["label"], full=e["full"], drive=d["id"], time=e["time"])
                 for d in out for e in d["regions"] if e["firstVisit"]]
    res = dict(
        rule="loc 점마다 regions.json 폴리곤 판정(even-odd). 미판정 점은 직전 지역 유지. "
             "새 지역은 30초 이상 연속 판정돼야 진입 확정(시각은 처음 들어간 점). 주행 시작 순 누적 firstVisit.",
        pointTests=results, drives=out, firstVisitsInOrder=new_order, totalDistinct=len(seen))
    with open(os.path.join(HERE, "out", "demo-region-sequence.json"), "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"== 첫 방문 {len(new_order)}곳: " + ", ".join(x["label"] for x in new_order))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
