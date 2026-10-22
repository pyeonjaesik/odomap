package com.odomap.core.run

import com.odomap.core.geo.LatLng
import com.odomap.core.geo.METERS_PER_DEGREE
import com.odomap.core.geo.RAD
import com.odomap.core.geo.haversineM
import com.odomap.core.geo.jsHypot
import com.odomap.core.geo.metersPerDegreeLng
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Port of clientApp src/services/run/runDistance.ts — only DopplerCheck and PathSmoother (Kalman filter + Doppler aiding +
// still-freeze + fixed-lag RTS smoothing), which the explore pipeline uses. Odometer/StepLog are not needed here.
// Based on the existing Kotlin port in clientApp/android/watch (metrics/RunDistance.kt, verified there against golden vectors),
// re-verified here end-to-end through the explore golden fixtures. Same names, same operation order.
// Same points in the same order always give the same answer.

private class Axis(val p: Double, val v: Double)

/** 한 축의 [[위치 분산, 공분산], [공분산, 속도 분산]] */
private class Cov(val a: Double, val b: Double, val c: Double)

private class SmoothState(val x: Double, val y: Double, val vx: Double, val vy: Double)

private class Step(
  val t: Double,
  /** 앞 단계에서 지난 시간(초) */
  val gapS: Double,
  val priorX: Axis,
  val priorY: Axis,
  val priorP: Cov,
  val postX: Axis,
  val postY: Axis,
  val postP: Cov,
  val weak: Boolean,
  val restart: Boolean,
  val aided: Boolean,
  val still: Boolean,
)
/** 다듬어 확정한(또는 잠정) 위치 하나 */
class SmoothedSample(
  val t: Double,
  override val lat: Double,
  override val lng: Double,
  val weak: Boolean,
  /** 바로 앞 위치에서 지난 시간(ms). 조각의 첫 위치면 0 */
  val gapMs: Double,
  /** 다듬은 속력(m/s) */
  val speed: Double,
  val aided: Boolean,
  val restart: Boolean,
  val still: Boolean,
) : LatLng

private fun predictCov(p: Cov, dt: Double, q: Double): Cov =
  Cov(
    a = p.a + 2 * dt * p.b + dt * dt * p.c + (q * dt * dt * dt) / 3,
    b = p.b + dt * p.c + (q * dt * dt) / 2,
    c = p.c + q * dt,
  )

/** 도플러 속도 확인(기록 하나에 하나). 세 배 넘게 어긋나는 일이 이어지면 그 뒤로 도플러를 쓰지 않는다 */
class DopplerCheck(private val f: RunFilter) {
  var usable = true
    private set

  private class Entry(val t: Double, val lat: Double, val lng: Double, val acc: Double?, val dopplerM: Double, val coveredS: Double)

  private val ring = ArrayList<Entry>()
  private var head = 0
  private var sumM = 0.0
  private var sumS = 0.0
  private var mismatch = 0

  fun observe(point: RawPoint) {
    if (!usable) return
    val previous = if (ring.size > head) ring[ring.size - 1] else null
    var dopplerM = 0.0
    var coveredS = 0.0
    if (previous != null) {
      val dt = (point.t - previous.t) / 1000
      val speed = point.speed
      if (speed != null && speed.isFinite() && dt <= 5) {
        dopplerM = speed * dt
        coveredS = dt
      }
    }
    ring.add(Entry(point.t, point.lat, point.lng, accuracyOf(point.acc), dopplerM, coveredS))
    sumM += dopplerM
    sumS += coveredS
    // 창보다 오래된 첫 점을 뺀다(두 점은 남긴다)
    val windowMs = f.dopplerCheckWindowS * 1000
    while (ring.size - head > 2 && point.t - ring[head + 1].t >= windowMs) {
      head += 1
      sumM -= ring[head].dopplerM
      sumS -= ring[head].coveredS
    }
    if (head > 64) {
      ring.subList(0, head).clear()
      head = 0
    }
    val first = ring[head]
    val span = point.t - first.t
    if (span < windowMs * 0.9 || span > windowMs * 1.5) return
    if (sumS < (span / 1000) * 0.8) return
    val moved = haversineM(first.lat, first.lng, point.lat, point.lng)
    val acc = max(first.acc ?: 0.0, accuracyOf(point.acc) ?: 0.0)
    val floorM = max(f.dopplerCheckMinMoveM, 2.5 * acc)
    val tooSlow = moved >= floorM && sumM < moved / 3
    val tooFast = sumM >= floorM && sumM > 3 * moved + floorM
    if (tooSlow || tooFast) {
      mismatch += 1
      if (mismatch >= f.dopplerMismatchLimit) usable = false
    } else {
      mismatch = 0
    }
  }
}

/**
 * 한 조각(일시정지 사이)의 위치를 다듬는다. add로 점을 시각순으로 넣고, 확정된 위치는 onSample로 내보낸다. tail()은 아직 확정하지 않은
 * 마지막 몇 점의 지금 추정(상태를 바꾸지 않음), close()는 남은 점을 모두 확정한다.
 */
class PathSmoother(
  private val f: RunFilter,
  private val maxSpeedMps: Double,
  private val doppler: DopplerCheck,
  private val onSample: (SmoothedSample) -> Unit,
) {
  private var originLat = 0.0
  private var originLng = 0.0
  private var kx = 0.0
  private var hasOrigin = false
  private var window = ArrayList<Step>()
  private var last: Step? = null
  private var speeds = ArrayList<Double?>()
  private var outliers = ArrayList<RawPoint>()
  private var restarting = false
  private var lastEmittedT: Double? = null
  private var lastSeenT: Double? = null
  private var lastAidedT: Double? = null

  fun add(point: RawPoint) {
    val acc = accuracyOf(point.acc)
    val last = this.last
    val gapS = if (last != null) (point.t - last.t) / 1000 else 0.0
    if (last == null || gapS > f.gapResetS) {
      // 처음이거나 오래 끊겼다: 오차가 작은 점에서 새로 시작한다
      if (acc != null && acc > f.firstPointMaxAccuracyM) return
      flushWindow()
      start(point, last != null)
      return
    }
    // 튄 점은 버린다(통계·물리)
    val dt = max(gapS, 0.05)
    val movingP = predictCov(last.postP, dt, processNoise(last))
    val z = toLocal(point)
    val zx = z[0]
    val zy = z[1]
    val ex = zx - (last.postX.p + last.postX.v * dt)
    val ey = zy - (last.postY.p + last.postY.v * dt)
    val r = positionVariance(acc)
    val innovation = jsHypot(ex, ey)
    val statistical = innovation > f.hardMinM && innovation * innovation > 2 * f.hardGate * f.hardGate * (movingP.a + r)
    val jump = jsHypot(zx - last.postX.p, zy - last.postY.p) - 3 * sqrt(r + last.postP.a)
    if (!restarting && (statistical || jump > maxSpeedMps * dt)) {
      onOutlier(point)
      return
    }
    if (outliers.isNotEmpty()) outliers = ArrayList()
    update(point, last, dt, gapS, movingP, zx, zy, r, acc, innovation)
  }

  /** 남은 점을 모두 확정한다(조각이 끝났을 때). */
  fun close() {
    flushWindow()
  }

  /** 마지막 점에서 windowMs 안에 도플러로 속도를 잡은 점이 있었는지(이 조각에서). */
  fun aidedWithin(windowMs: Double): Boolean {
    val aidedT = lastAidedT ?: return false
    val lastStep = last ?: return false
    return lastStep.t - aidedT <= windowMs
  }

  /** 아직 확정하지 않은 점들의 지금 추정(상태를 바꾸지 않는다). */
  fun tail(): MutableList<SmoothedSample> {
    if (window.isEmpty()) return ArrayList()
    val smoothed = backward(false)
    var previousT = lastEmittedT
    val out = ArrayList<SmoothedSample>(window.size)
    for (i in window.indices) {
      val step = window[i]
      out.add(sampleOf(step, smoothed[i], previousT))
      previousT = step.t
    }
    return out
  }

  // ── 안쪽

  private fun toLocal(point: RawPoint): DoubleArray {
    if (!hasOrigin) {
      hasOrigin = true
      originLat = point.lat
      originLng = point.lng
      kx = metersPerDegreeLng(point.lat)
    }
    return doubleArrayOf((point.lng - originLng) * kx, (point.lat - originLat) * METERS_PER_DEGREE)
  }

  private fun positionVariance(acc: Double?): Double {
    val sigma = min(f.maxPositionSigmaM, max(f.minPositionSigmaM, (acc ?: f.defaultAccuracyM) * f.accuracyToSigma))
    return sigma * sigma
  }

  /** 가속도 잡음 밀도: 지금 속도로 정한다 */
  private fun processNoise(state: Step): Double {
    val v2 = state.postX.v * state.postX.v + state.postY.v * state.postY.v
    return f.processNoise + f.processNoiseSpeedK * v2
  }

  private class Velocity(val vx: Double, val vy: Double, val r: Double)

  /** 도플러 속도(동·북, m/s)와 축마다의 분산. 느리면 '방향 모를 느린 속도', 빠른데 방향이 없으면 쓰지 않는다 */
  private fun dopplerVelocity(point: RawPoint): Velocity? {
    if (!(f.dopplerAiding > 0) || !doppler.usable) return null
    val speed = point.speed
    if (speed == null || !speed.isFinite() || speed < 0) return null
    val sacc = if (finite(point.sacc) && point.sacc!! > 0) point.sacc else f.dopplerDefaultSigmaMps
    val base = sacc * sacc + f.dopplerBodySigmaMps * f.dopplerBodySigmaMps
    if (speed < f.dopplerMinSpeedMps) return Velocity(0.0, 0.0, base + (speed * speed) / 2)
    val course = point.course
    if (course == null || !course.isFinite() || course < 0) return null
    val cross = speed * f.dopplerCourseSigmaDeg * RAD
    return Velocity(speed * StrictMath.sin(course * RAD), speed * StrictMath.cos(course * RAD), base + cross * cross)
  }

  /** 도플러로 본 정지: 최근 stillCount개 속도가 모두 있고, 평균이 stillSpeedMps보다 느리고, 가장 빠른 것도 stillMaxSpeedMps 아래 */
  private fun trackStill(point: RawPoint): Boolean {
    val speed = point.speed
    val usable = f.dopplerAiding > 0 && doppler.usable && speed != null && speed.isFinite() && speed >= 0
    speeds.add(if (usable) speed else null)
    val count = max(1.0, floor(f.stillCount)).toInt()
    while (speeds.size > count) speeds.removeAt(0)
    if (speeds.size < count) return false
    var sum = 0.0
    for (value in speeds) {
      if (value == null || value >= f.stillMaxSpeedMps) return false
      sum += value
    }
    return sum / count < f.stillSpeedMps
  }

  private fun start(point: RawPoint, afterGap: Boolean) {
    doppler.observe(point)
    val z = toLocal(point)
    val acc = accuracyOf(point.acc)
    val r = positionVariance(acc)
    val velocity = dopplerVelocity(point)
    outliers = ArrayList()
    speeds = ArrayList()
    trackStill(point)
    val x = Axis(z[0], velocity?.vx ?: 0.0)
    val y = Axis(z[1], velocity?.vy ?: 0.0)
    val p = Cov(r, 0.0, if (velocity != null) velocity.r else f.initialSpeedSigmaMps * f.initialSpeedSigmaMps)
    val seen = lastSeenT
    push(
      Step(
        t = point.t,
        gapS = if (seen == null) 0.0 else (point.t - seen) / 1000,
        priorX = x,
        priorY = y,
        priorP = p,
        postX = x,
        postY = y,
        postP = p,
        weak = afterGap || (acc ?: 0.0) > f.weakAccuracyM,
        restart = true,
        aided = velocity != null,
        still = false,
      ),
    )
  }

  private fun update(
    point: RawPoint,
    last: Step,
    dt: Double,
    gapS: Double,
    movingP: Cov,
    zx: Double,
    zy: Double,
    r: Double,
    acc: Double?,
    innovation: Double,
  ) {
    doppler.observe(point)
    // 멈춰 있으면(도플러) 위치를 얼린다. 위치가 크게 벗어나면 얼리지 않는다
    val still = trackStill(point) && innovation < max(f.stillBreakM, 3 * sqrt(movingP.a + r))
    val priorP = if (still) predictCov(last.postP, dt, 0.0) else movingP
    val priorX = Axis(last.postX.p + last.postX.v * dt, last.postX.v)
    val priorY = Axis(last.postY.p + last.postY.v * dt, last.postY.v)
    var xp = priorX.p
    var xv = priorX.v
    var yp = priorY.p
    var yv = priorY.v
    var pa = priorP.a
    var pb = priorP.b
    var pc = priorP.c
    val gate2 = 2 * f.robustGate * f.robustGate
    var downWeighted = false
    if (!still) {
      // 위치: 잔차가 크면 오차를 키워 무게를 줄인다(Huber)
      val ex = zx - xp
      val ey = zy - yp
      val e2 = ex * ex + ey * ey
      var rUse = r
      if (e2 > gate2 * (pa + r)) {
        rUse = max(r, e2 / gate2 - pa)
        downWeighted = true
      }
      val s = pa + rUse
      val k0 = pa / s
      val k1 = pb / s
      xp += k0 * ex
      xv += k1 * ex
      yp += k0 * ey
      yv += k1 * ey
      val na = (1 - k0) * pa
      val nb = (1 - k0) * pb
      val nc = pc - k1 * pb
      pa = na
      pb = nb
      pc = nc
    }
    fun velocityUpdate(vx: Double, vy: Double, rv: Double) {
      val dx = vx - xv
      val dy = vy - yv
      val d2 = dx * dx + dy * dy
      if (d2 > 2 * f.hardGate * f.hardGate * (pc + rv)) return // 말이 안 되는 속도는 버린다
      var rv2 = rv
      if (d2 > gate2 * (pc + rv2)) rv2 = max(rv2, d2 / gate2 - pc)
      val s = pc + rv2
      val k0 = pb / s
      val k1 = pc / s
      xp += k0 * dx
      xv += k1 * dx
      yp += k0 * dy
      yv += k1 * dy
      val na = pa - k0 * pb
      val nb = (1 - k1) * pb
      val nc = (1 - k1) * pc
      pa = na
      pb = nb
      pc = nc
    }
    var aided = still
    if (still) {
      velocityUpdate(0.0, 0.0, f.stillSigmaMps * f.stillSigmaMps)
    } else {
      val velocity = dopplerVelocity(point)
      if (velocity != null) {
        velocityUpdate(velocity.vx, velocity.vy, velocity.r)
        aided = true
      }
    }
    push(
      Step(
        t = point.t,
        gapS = gapS,
        priorX = priorX,
        priorY = priorY,
        priorP = priorP,
        postX = Axis(xp, xv),
        postY = Axis(yp, yv),
        postP = Cov(pa, pb, pc),
        weak = (acc ?: 0.0) > f.weakAccuracyM || gapS > f.weakGapS || downWeighted,
        restart = false,
        aided = aided,
        still = still,
      ),
    )
  }

  private fun onOutlier(point: RawPoint) {
    val previous = outliers.lastOrNull()
    val consistent =
      previous != null && haversineM(previous, point) / max((point.t - previous.t) / 1000, 1.0) <= maxSpeedMps
    if (consistent) {
      outliers.add(point)
    } else {
      outliers = arrayListOf(point)
    }
    if (outliers.size < max(2.0, floor(f.outlierRecoveryCount))) return
    // 필터가 틀렸다: 버린 점들로 새로 시작한다
    val run = outliers
    outliers = ArrayList()
    flushWindow()
    last = null
    restarting = true
    for (p in run) {
      if (last == null) {
        if ((accuracyOf(p.acc) ?: 0.0) > f.firstPointMaxAccuracyM) continue
        start(p, true)
      } else {
        add(p)
      }
    }
    restarting = false
  }

  private fun push(step: Step) {
    window.add(step)
    last = step
    lastSeenT = step.t
    if (step.aided) lastAidedT = step.t
    if (window.size > f.smoothingLag) {
      emit(window[0], backward(true)[0])
      window.removeAt(0)
    }
  }

  private fun flushWindow() {
    if (window.isEmpty()) return
    val smoothed = backward(false)
    for (i in window.indices) emit(window[i], smoothed[i])
    window = ArrayList()
  }

  private fun emit(step: Step, state: SmoothState) {
    val sample = sampleOf(step, state, lastEmittedT)
    lastEmittedT = step.t
    onSample(sample)
  }

  private fun sampleOf(step: Step, state: SmoothState, previousT: Double?): SmoothedSample =
    SmoothedSample(
      t = step.t,
      lat = originLat + state.y / METERS_PER_DEGREE,
      lng = originLng + (if (kx == 0.0) 0.0 else state.x / kx),
      weak = step.weak,
      gapMs = if (previousT == null) 0.0 else step.t - previousT,
      speed = jsHypot(state.vx, state.vy),
      aided = step.aided,
      restart = step.restart,
      still = step.still,
    )

  /** RTS 뒤로 훑기. 새로 시작한 단계에서는 끊는다. onlyFirst면 첫 점 하나만 */
  private fun backward(onlyFirst: Boolean): List<SmoothState> {
    val w = window
    val n = w.size
    val out: Array<SmoothState?>? = if (onlyFirst) null else arrayOfNulls(n)
    val end = w[n - 1]
    var sxp = end.postX.p
    var sxv = end.postX.v
    var syp = end.postY.p
    var syv = end.postY.v
    out?.set(n - 1, SmoothState(sxp, syp, sxv, syv))
    var k = n - 2
    while (k >= 0) {
      val cur = w[k]
      val next = w[k + 1]
      val q = next.priorP
      val det = q.a * q.c - q.b * q.b
      if (next.restart || !(det > 0)) {
        sxp = cur.postX.p
        sxv = cur.postX.v
        syp = cur.postY.p
        syv = cur.postY.v
      } else {
        val dt = max(next.gapS, 0.05)
        val p = cur.postP
        // C = P Fᵀ Q⁻¹, F = [[1, dt], [0, 1]]
        val m00 = p.a + p.b * dt
        val m01 = p.b
        val m10 = p.b + p.c * dt
        val m11 = p.c
        val i00 = q.c / det
        val i01 = -q.b / det
        val i11 = q.a / det
        val c00 = m00 * i00 + m01 * i01
        val c01 = m00 * i01 + m01 * i11
        val c10 = m10 * i00 + m11 * i01
        val c11 = m10 * i01 + m11 * i11
        val dxp = sxp - next.priorX.p
        val dxv = sxv - next.priorX.v
        val dyp = syp - next.priorY.p
        val dyv = syv - next.priorY.v
        sxp = cur.postX.p + c00 * dxp + c01 * dxv
        sxv = cur.postX.v + c10 * dxp + c11 * dxv
        syp = cur.postY.p + c00 * dyp + c01 * dyv
        syv = cur.postY.v + c10 * dyp + c11 * dyv
      }
      out?.set(k, SmoothState(sxp, syp, sxv, syv))
      k -= 1
    }
    if (onlyFirst) return listOf(SmoothState(sxp, syp, sxv, syv))
    return out!!.map { it!! }
  }
}

