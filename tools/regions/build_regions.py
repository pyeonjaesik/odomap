#!/usr/bin/env python3
"""시·군·구 경계 앱 에셋 만들기 (오프라인 point-in-polygon 용).

원자료: vuski/admdongkor 행정동 GeoJSON (통계청 SGIS 행정동 경계 가공, CC BY 4.0 / 공공누리 1유형).
행정동 -> 시·군·구 단위로 합치고(일반구는 상위 시로 병합), 이웃 경계를 공유한 채
(coverage simplification) 단순화해서 내보낸다.

출력 (out/):
  regions.json          앱 에셋. 좌표는 정수 마이크로도(lat*1e6, lng*1e6).
  regions-summary.json  시도별 개수, 총 개수, 정확도 통계.
  regions-outline.json  전국 줌 '정복 지도'용 거친 시도 윤곽 + 시군구 폴리곤.

필요: python3 + shapely>=2.1 (GEOS>=3.12, coverage_simplify).  pip install --user shapely
사용: python3 -I build_regions.py [--version ver20261001] [--tol 60]
"""
import argparse
import json
import math
import os
import re
import sys
import urllib.request
import warnings
from collections import OrderedDict, defaultdict

import numpy as np
import shapely
from shapely.geometry import shape
from shapely.ops import polylabel

warnings.filterwarnings("ignore", category=RuntimeWarning)

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw", "admdongkor")
OUT = os.path.join(HERE, "out")
SRC_URL = "https://raw.githubusercontent.com/vuski/admdongkor/master/{v}/HangJeongDong_{v}.geojson"

ATTRIBUTION_KO = ("본 데이터는 통계청 통계지리정보서비스(SGIS, https://sgis.kostat.go.kr)에서 공공누리 제1유형으로 "
                  "개방한 행정동 경계를 가공한 것이며(가공: vuski/admdongkor, https://github.com/vuski/admdongkor), "
                  "CC BY 4.0으로 배포됩니다. 시·군·구 단위 병합·단순화: Odomap.")
ATTRIBUTION_EN = ("Derived from administrative-dong boundaries released by Statistics Korea SGIS "
                  "(https://sgis.kostat.go.kr) under KOGL Type 1, modified by vuski/admdongkor "
                  "(https://github.com/vuski/admdongkor), distributed under CC BY 4.0. "
                  "Aggregated to si/gun/gu and simplified by Odomap.")

# 광역시·특별시(자치구가 기초자치단체)인 시도: 여기의 '구'는 병합하지 않는다.
METRO_SIDO = {"11", "26", "27", "28", "29", "30", "31", "12"}  # 12 = 전남광주통합특별시(2026-07)
SIDO_SHORT = {"11": "서울", "26": "부산", "27": "대구", "28": "인천", "30": "대전", "31": "울산",
              "36": "세종", "41": "경기", "51": "강원", "43": "충북", "44": "충남", "47": "경북",
              "48": "경남", "50": "제주", "52": "전북"}

# 평면 근사(등장방형, 위도 36° 기준) — 한국 범위에서 거리 오차 ±4% 이내. 단위 m.
LAT0 = math.radians(36.0)
KX = 111320.0 * math.cos(LAT0)
KY = 110574.0


def fwd(c):
    return np.column_stack([c[:, 0] * KX, c[:, 1] * KY])


def inv(c):
    return np.column_stack([c[:, 0] / KX, c[:, 1] / KY])


def parts(g):
    if g.is_empty:
        return []
    if g.geom_type == "Polygon":
        return [g]
    out = []
    for p in getattr(g, "geoms", []):
        out.extend(parts(p))
    return out


def sido_short(sido_code, sgg_code, sgg_name):
    if sido_code == "12":  # 전남광주통합특별시: 옛 광주광역시 자치구는 '광주', 나머지는 '전남'
        return "광주" if sgg_name.endswith("구") else "전남"
    return SIDO_SHORT.get(sido_code, "")


def load_units(path):
    """행정동 -> 단위(시·군·구). 일반구는 상위 시 코드(끝자리 0)로 병합."""
    with open(path, encoding="utf-8") as f:
        fc = json.load(f)
    meta = OrderedDict()
    groups = defaultdict(list)
    for feat in fc["features"]:
        p = feat["properties"]
        code, name, sido = p["sgg"], p["sggnm"], p["sido"]
        districts = None
        m = re.match(r"^(.+?시)(.+구)$", name)
        if m and sido not in METRO_SIDO:  # 수원시장안구 -> 수원시
            code, name, districts = code[:4] + "0", m.group(1), m.group(2)
        if code not in meta:
            full = f"{p['sidonm']} {name}"
            label = f"{sido_short(sido, code, name)} {name}"
            if sido == "36":
                full, label = p["sidonm"], name  # 세종특별자치시 (단층제) / "세종시"
            meta[code] = dict(code=code, name=name, full=full, sido=p["sidonm"], sidoCode=sido,
                              label=label, districts=[])
        if districts and districts not in meta[code]["districts"]:
            meta[code]["districts"].append(districts)
        groups[code].append(shape(feat["geometry"]))
    codes = sorted(meta)
    units = []
    for c in codes:
        u = shapely.make_valid(shapely.transform(shapely.union_all(groups[c]), fwd))
        units.append(u)
    return codes, meta, units, fc


def clean_units(units, island_min_m2, sliver_hole_m2=5000.0, keep=lambda p: False):
    """작은 구멍(디졸브 틈) 제거, 이웃과 닿지 않는 작은 섬 제거, 겹침 제거."""
    tree = shapely.STRtree(units)
    out = []
    for i, u in enumerate(units):
        kept = []
        for p in parts(u):
            p = shapely.Polygon(p.exterior, [r for r in p.interiors if shapely.Polygon(r).area > sliver_hole_m2])
            if p.area < island_min_m2 and not keep(p):
                if not [k for k in tree.query(p, predicate="intersects") if k != i]:
                    continue
            kept.append(p)
        if not kept:
            kept = [max(parts(u), key=lambda q: q.area)]
        out.append(shapely.MultiPolygon(kept))
    # 겹침 제거(원자료의 수 m² 수준 겹침): 코드 순서상 뒤쪽에서 뺀다
    tree = shapely.STRtree(out)
    a, b = tree.query(out, predicate="overlaps")
    for i, j in zip(a, b):
        if i < j:
            d = shapely.difference(out[j], out[i])
            out[j] = shapely.MultiPolygon([p for p in parts(d) if p.area > 1])
    return out


def to_micro_rings(poly):
    """Polygon -> [exterior, holes...], 각 ring = [lat0,lng0,lat1,lng1,...] (닫는 점 생략)."""
    rings = []
    for r in [poly.exterior, *poly.interiors]:
        c = inv(np.asarray(r.coords))
        if len(c) > 1 and c[0][0] == c[-1][0] and c[0][1] == c[-1][1]:
            c = c[:-1]
        flat = []
        prev = None
        for lng, lat in c:
            pt = (int(round(lat * 1e6)), int(round(lng * 1e6)))
            if pt != prev:
                flat.extend(pt)
            prev = pt
        if len(flat) >= 6:
            rings.append(flat)
    return rings


def bbox_micro(g):
    x0, y0, x1, y1 = g.bounds
    return [int(math.floor(y0 / KY * 1e6)), int(math.floor(x0 / KX * 1e6)),
            int(math.ceil(y1 / KY * 1e6)), int(math.ceil(x1 / KX * 1e6))]


def center_of(g):
    big = max(parts(g), key=lambda p: p.area)
    c = polylabel(big, tolerance=50.0)
    return [round(c.y / KY, 6), round(c.x / KX, 6)]


def dump_lines(path, head, items, key="regions"):
    """사람이 diff 볼 수 있게 지역 하나당 한 줄."""
    with open(path, "w", encoding="utf-8") as f:
        h = json.dumps(head, ensure_ascii=False, separators=(",", ":"))
        f.write(h[:-1] + f',"{key}":[\n')
        f.write(",\n".join(json.dumps(it, ensure_ascii=False, separators=(",", ":")) for it in items))
        f.write("\n]}\n")
    return os.path.getsize(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="ver20261001")
    ap.add_argument("--tol", type=float, default=60.0, help="coverage simplify tolerance (m), 앱 에셋")
    ap.add_argument("--island-min", type=float, default=20000.0, help="이웃 없는 섬 최소 면적(m²), 앱 에셋")
    ap.add_argument("--outline-tol", type=float, default=700.0)
    ap.add_argument("--outline-island-min", type=float, default=1.5e6)
    args = ap.parse_args()

    os.makedirs(RAW, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    src = os.path.join(RAW, f"HangJeongDong_{args.version}.geojson")
    url = SRC_URL.format(v=args.version)
    if not os.path.exists(src):
        print("download", url)
        urllib.request.urlretrieve(url, src)

    codes, meta, units, fc = load_units(src)
    print(f"행정동 {len(fc['features'])} -> 단위 {len(codes)}")
    source = dict(name="vuski/admdongkor (통계청 SGIS 행정동 경계 가공)", url=url, version=args.version,
                  license="CC BY 4.0 (원자료: 공공누리 제1유형, 통계청 SGIS)", attribution=ATTRIBUTION_KO,
                  attributionEn=ATTRIBUTION_EN)

    # ---------- 앱 에셋 ----------
    base = clean_units(units, args.island_min)
    simp = shapely.coverage_simplify(np.array(base, dtype=object), args.tol)
    simp = [shapely.make_valid(s) for s in simp]
    regions = []
    for c, b, s in zip(codes, base, simp):
        m = meta[c]
        polys = [to_micro_rings(p) for p in parts(s)]
        polys = [p for p in polys if p]
        r = OrderedDict(code=c, name=m["name"], full=m["full"], label=m["label"], sido=m["sido"],
                        sidoCode=m["sidoCode"])
        if m["districts"]:
            r["districts"] = m["districts"]
        r["center"] = center_of(s)
        r["bbox"] = bbox_micro(s)
        r["polygons"] = polys
        regions.append(r)

    # 정확도 통계 (원본 단위 경계 vs 단순화 경계, m)
    hd = np.array([shapely.hausdorff_distance(a.boundary, b.boundary, densify=0.25) for a, b in zip(base, simp)])
    npts = sum(len(r) // 2 for reg in regions for p in reg["polygons"] for r in p)
    head = OrderedDict(
        format="odomap-regions/1",
        coords="정수 마이크로도. ring = [lat0,lng0,lat1,lng1,...] (위도 먼저, 닫는 점 생략). "
               "polygons = [[외곽ring, 구멍ring...], ...]. bbox = [minLat,minLng,maxLat,maxLng] 마이크로도. "
               "center = [lat,lng] 도(폴리곤 내부 보장, pole of inaccessibility). 판정은 even-odd 규칙.",
        unit="시·군·구(기초자치단체 + 세종 + 제주 행정시). 일반구는 상위 시로 병합(districts에 기록).",
        source=source, simplifyToleranceM=args.tol, count=len(regions), points=npts,
    )
    size = dump_lines(os.path.join(OUT, "regions.json"), head, regions)
    print(f"regions.json {size/1e6:.2f} MB, {npts} pts, hausdorff m: med {np.median(hd):.0f} p90 "
          f"{np.percentile(hd, 90):.0f} p99 {np.percentile(hd, 99):.0f} max {hd.max():.0f} ({codes[int(hd.argmax())]})")

    # 이웃 경계 일관성: 단순화 후 겹침 면적 합
    tree = shapely.STRtree(simp)
    a, b = tree.query(simp, predicate="intersects")
    overlap = sum(shapely.area(shapely.intersection(simp[i], simp[j])) for i, j in zip(a, b) if i < j)

    # ---------- 개요 지도용 거친 윤곽 ----------
    dokdo = lambda p: p.centroid.x / KX > 131.5  # 독도는 크기와 무관하게 유지
    coarse_base = clean_units(units, args.outline_island_min, keep=dokdo)
    coarse = [shapely.make_valid(s) for s in
              shapely.coverage_simplify(np.array(coarse_base, dtype=object), args.outline_tol)]
    by_sido = defaultdict(list)
    for c, g in zip(codes, coarse):
        by_sido[meta[c]["sidoCode"]].append(g)
    sido_items = []
    for sc, gs in by_sido.items():
        u = shapely.coverage_union_all(gs) if hasattr(shapely, "coverage_union_all") else shapely.union_all(gs)
        u = shapely.make_valid(u)
        name = next(meta[c]["sido"] for c in codes if meta[c]["sidoCode"] == sc)
        sido_items.append(OrderedDict(sidoCode=sc, sido=name,
                                      polygons=[x for x in (to_micro_rings(p) for p in parts(u) if p.area > 1e5 or dokdo(p)) if x]))
    outline_regions = [OrderedDict(code=c, polygons=[x for x in (to_micro_rings(p) for p in parts(g)) if x])
                       for c, g in zip(codes, coarse)]
    ohead = OrderedDict(format="odomap-regions-outline/1",
                        coords="regions.json과 동일(정수 마이크로도, [lat,lng,...] 평탄 ring). 화면 표시 전용 — 판정에 쓰지 말 것.",
                        source=source, simplifyToleranceM=args.outline_tol,
                        sido=sido_items)
    osize = dump_lines(os.path.join(OUT, "regions-outline.json"), ohead, outline_regions)
    print(f"regions-outline.json {osize/1e3:.0f} KB")

    # ---------- 요약 ----------
    order = []
    for c in codes:
        sc = meta[c]["sidoCode"]
        if sc not in order:
            order.append(sc)
    order.sort(key=lambda s: list(SIDO_SHORT).index(s) if s in SIDO_SHORT else 3.5)  # 12 = 광주 자리(인천 다음)
    by = OrderedDict()
    for sc in order:
        cs = [c for c in codes if meta[c]["sidoCode"] == sc]
        by[meta[cs[0]]["sido"]] = OrderedDict(
            sidoCode=sc, count=len(cs),
            si=sum(1 for c in cs if meta[c]["name"].endswith("시")),
            gun=sum(1 for c in cs if meta[c]["name"].endswith("군")),
            gu=sum(1 for c in cs if meta[c]["name"].endswith("구")),
            names=[meta[c]["name"] for c in cs])
    merged = OrderedDict((meta[c]["full"], meta[c]["districts"]) for c in codes if meta[c]["districts"])
    summary = OrderedDict(
        total=len(codes),
        note="단위 = 기초자치단체(시·군·자치구) + 세종특별자치시 + 제주 행정시 2곳. 일반구는 상위 시로 병합.",
        breakdown=OrderedDict(
            autonomous=len(codes) - 1 - 2, sejong=1, jejuAdminCities=2,
            si=sum(1 for c in codes if meta[c]["name"].endswith("시")),
            gun=sum(1 for c in codes if meta[c]["name"].endswith("군")),
            gu=sum(1 for c in codes if meta[c]["name"].endswith("구"))),
        bySido=by, mergedGeneralDistricts=merged,
        source=source,
        files=OrderedDict(regionsJsonBytes=size, regionsOutlineJsonBytes=osize, regionsPoints=npts),
        accuracy=OrderedDict(
            method="원본(행정동 디졸브) 경계와 단순화 경계 사이 Hausdorff 거리(m), 지역별",
            median=round(float(np.median(hd))), p90=round(float(np.percentile(hd, 90))),
            p99=round(float(np.percentile(hd, 99))), max=round(float(hd.max())),
            maxAt=meta[codes[int(hd.argmax())]]["full"],
            neighbourOverlapM2=round(float(overlap))),
    )
    with open(os.path.join(OUT, "regions-summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("total", len(codes), {k: v["count"] for k, v in by.items()})

    # 판정 일치율: 원본 행정동 경계 내부 무작위 점을 단순화 폴리곤으로 판정
    rng = np.random.default_rng(7)
    tree_s = shapely.STRtree(simp)
    land = shapely.union_all(base)
    x0, y0, x1, y1 = land.bounds
    tested = wrong = outside = 0
    while tested < 20000:
        xs = rng.uniform(x0, x1, 5000)
        ys = rng.uniform(y0, y1, 5000)
        pts = shapely.points(xs, ys)
        orig_idx = shapely.STRtree(base).query(pts, predicate="within")
        for pi, ui in zip(*orig_idx):
            hit = tree_s.query(pts[pi], predicate="within")
            tested += 1
            if len(hit) == 0:
                outside += 1
            elif ui not in hit:
                wrong += 1
    print(f"random land points {tested}: other-region {wrong} ({wrong/tested*100:.3f}%), "
          f"outside-all {outside} ({outside/tested*100:.3f}%)")
    summary["accuracy"]["randomPoints"] = OrderedDict(tested=tested, otherRegion=wrong, outsideAll=outside)
    with open(os.path.join(OUT, "regions-summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
        f.write("\n")


if __name__ == "__main__":
    sys.exit(main())
