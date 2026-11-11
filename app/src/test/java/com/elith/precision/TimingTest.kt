package com.elith.precision

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimingTest {
    private val zone = ZoneId.of("Asia/Kuala_Lumpur")
    private fun at(s: String, z: ZoneId = zone) = LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
    @Test fun midnightRollsAcrossDateBoundary() {
        assertEquals(at("2026-11-12T00:00:00"), TargetSchedule.next(at("2026-11-11T23:59:57.500"), zone, LocalTime.MIDNIGHT))
        assertEquals(at("2026-11-12T00:00:00"), TargetSchedule.next(at("2026-11-11T01:00:00"), zone, LocalTime.MIDNIGHT))
    }
    @Test fun timeOnlyAlwaysChoosesStrictlyFuture() {
        assertEquals(at("2026-11-12T12:00:00"), TargetSchedule.next(at("2026-11-11T12:00:00"), zone, LocalTime.NOON))
    }
    @Test fun millisecondsArePreserved() {
        assertEquals(at("2026-11-11T12:00:00.123"), TargetSchedule.next(at("2026-11-11T12:00:00"), zone, LocalTime.of(12,0,0,123000000)))
    }
    @Test fun timezoneChangeUsesDailyLocalTime() {
        val now = Instant.parse("2026-11-11T10:00:00Z").toEpochMilli()
        assertNotEquals(TargetSchedule.next(now, zone, LocalTime.NOON), TargetSchedule.next(now, ZoneId.of("UTC"), LocalTime.NOON))
    }
    @Test fun dstGapMovesForwardAndOverlapRunsOnce() {
        val ny = ZoneId.of("America/New_York")
        assertEquals(at("2026-03-08T03:30:00", ny), TargetSchedule.next(at("2026-03-08T00:00:00", ny), ny, LocalTime.of(2,30)))
        val first = TargetSchedule.next(at("2026-11-01T00:00:00",ny), ny, LocalTime.of(1,30))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), first)
        assertEquals(at("2026-11-02T01:30:00",ny), TargetSchedule.next(first+1,ny,LocalTime.of(1,30)))
    }
    @Test fun timelineOnlyDuringFinalThreeSeconds() {
        for (remaining in listOf(100000L,3001L,0L,-1L)) assertFalse(CountdownFrame.of(remaining).timeline)
        for (remaining in listOf(3000L,2999L,2500L,1L)) assertTrue(CountdownFrame.of(remaining).timeline)
    }
    @Test fun checkpointsActivateAtExactThresholds() {
        assertEquals(listOf(2500L,2000L,1500L,1000L,500L), CountdownFrame.thresholds.toList())
        assertEquals(0,CountdownFrame.of(3000).activeCount)
        for (i in 0..4) {
            val t=CountdownFrame.thresholds[i]
            assertEquals(i,CountdownFrame.of(t+1).activeCount)
            assertEquals(i+1,CountdownFrame.of(t).activeCount)
        }
        assertEquals(0f,CountdownFrame.of(3000).progress,0f)
        assertEquals(0.5f,CountdownFrame.of(1500).progress,0.0001f)
    }
    @Test fun formattingCeilsSoZeroIsNeverShownBeforeTarget() {
        assertEquals("00:00:02.5",formatCountdown(2500,1))
        assertEquals("00:00:02.5",formatCountdown(2401,1))
        assertEquals("00:00:00.1",formatCountdown(1,1))
        assertEquals("00:00:00.00",formatCountdown(-20,2))
        assertEquals("00:00:01.123",formatCountdown(1123,3))
        assertEquals("100:00:00.0",formatCountdown(360000000,1))
    }
    @Test fun oneTimeCompletionLatchesAndNeverGoesNegative() {
        val s=CountdownSession(10000,false,LocalTime.NOON)
        assertEquals(1L,s.frame(9999,1,zone).remainingMs)
        assertEquals(0L,s.frame(10000,2,zone).remainingMs)
        assertEquals(0L,s.frame(9000,3,zone).remainingMs)
    }
    @Test fun dailyHoldsZeroThenResetsWithoutDrift() {
        val target=at("2026-11-12T00:00:00")
        val s=CountdownSession(target,true,LocalTime.MIDNIGHT)
        assertEquals(0L,s.frame(target,100,zone).remainingMs)
        assertEquals(0L,s.frame(target+1100,1200,zone).remainingMs)
        assertEquals(86398800L,s.frame(target+1200,1300,zone).remainingMs)
        assertEquals(at("2026-11-13T00:00:00"),s.targetMs)
    }
    @Test fun resumedDailySkipsMissedDaysAndHandlesNewZone() {
        val target=at("2026-11-12T00:00:00")
        val s=CountdownSession(target,true,LocalTime.MIDNIGHT)
        val now=target+3*86400000+1000
        s.frame(now,100,zone)
        assertTrue(s.frame(now+1300,1400,zone).remainingMs>0)
        s.reconcile(now+1300,ZoneId.of("UTC"))
        assertEquals(TargetSchedule.next(now+1300,ZoneId.of("UTC"),LocalTime.MIDNIGHT),s.targetMs)
    }
    @Test fun disciplinedClockFollowsWallAdjustmentsAndResume() {
        val c=DisciplinedClock()
        assertEquals(1000L,c.now(1000,0))
        assertEquals(1010L,c.now(1010,10000000))
        assertEquals(5010L,c.now(5010,20000000))
        assertEquals(110L,c.now(110,30000000))
        assertEquals(60000L,c.now(60000,59000000000))
    }
    @Test fun interpolatedClockNeverFinishesAheadOfWallClock() {
        val c=DisciplinedClock()
        c.now(1000,0)
        assertEquals(1010L,c.now(1010,12000000))
    }
    @Test fun networkRatesAreMeasuredAndUnavailableIsExplicit() {
        val m=TrafficMeter()
        assertNull(m.sample(1000,2000,0))
        assertEquals(1024.0,m.sample(2024,3024,1000)!!.rx,0.01)
        assertNull(m.sample(-1,-1,2000))
        assertEquals("1 KB/s",formatRate(1024.0))
        assertEquals("1 MB/s",formatRate(1048576.0))
        assertEquals("35 B/s",formatRate(35.0))
        assertNull(m.sample(1,1,3000))
    }
}
