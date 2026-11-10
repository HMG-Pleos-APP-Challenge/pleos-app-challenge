package com.daehyundai.map.feature.conquest.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Feature-internal time policy. All inputs/outputs are UTC epoch milliseconds. */
object ConquestTime {
    private val zone = ZoneId.of("Asia/Seoul")
    const val WINDOW_MILLIS = 36L * 60 * 60 * 1000

    data class HalfDay(val date: LocalDate, val afternoon: Boolean)

    fun halfDay(timestampUtcMs: Long): HalfDay {
        require(timestampUtcMs >= 0)
        val local = Instant.ofEpochMilli(timestampUtcMs).atZone(zone)
        return HalfDay(local.toLocalDate(), local.hour >= 12)
    }

    fun latestSettlement(timestampUtcMs: Long): Long {
        require(timestampUtcMs >= 0)
        val local = Instant.ofEpochMilli(timestampUtcMs).atZone(zone)
        val date = if (local.hour < 12) local.toLocalDate().minusDays(1) else local.toLocalDate()
        return date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    }

    fun nextSettlement(settlementUtcMs: Long): Long {
        require(latestSettlement(settlementUtcMs) == settlementUtcMs)
        return Instant.ofEpochMilli(settlementUtcMs).atZone(zone).plusDays(1).toInstant().toEpochMilli()
    }

    fun inWindow(timestampUtcMs: Long, settlementUtcMs: Long): Boolean =
        timestampUtcMs >= settlementUtcMs - WINDOW_MILLIS && timestampUtcMs < settlementUtcMs
}
