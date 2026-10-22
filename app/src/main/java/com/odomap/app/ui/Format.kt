package com.odomap.app.ui

import com.odomap.app.engine.formatKm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun km(m: Double): String = formatKm(m)

/** Korean market app: times in Seoul time like the engine's day numbers (exploreDayOf), whatever zone the head unit / emulator is set to */
private val SEOUL: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
private val dateFmt = ThreadLocal.withInitial { SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREAN).apply { timeZone = SEOUL } }
private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.KOREAN).apply { timeZone = SEOUL } }

fun dateTime(ms: Long): String = dateFmt.get()!!.format(Date(ms))

fun clock(ms: Long): String = timeFmt.get()!!.format(Date(ms))

/** minutes rounded, at least 1 */
fun minutesOf(fromMs: Long, toMs: Long): Int = maxOf(1, Math.round((toMs - fromMs) / 60000.0).toInt())
