/*
 * Copyright (C) 2014 The Flogger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.flogger;

import static com.google.common.flogger.util.Checks.checkNotNull;

import com.google.common.flogger.backend.LogData;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Statistics for individual log sites for determining when rate limited log statements should be
 * emitted. This class is mutable, but thread safe.
 */
final class LogSiteStats {
  /** Creates a period for rate limiting for the specified duration. */
  static RateLimitPeriod newRateLimitPeriod(int n, TimeUnit unit) {
    return new RateLimitPeriod(n, unit);
  }

  /**
   * Immutable metadata for rate limiting based on a fixed count. This corresponds to the
   * LOG_AT_MOST_EVERY metadata key in {@link LogData}. Unlike the metadata for {@code every(N)},
   * we need to use a wrapper class here to preserve the time unit information.
   */
  // TODO: Consider making this a public class to allow backends to handle it explicitly.
  static final class RateLimitPeriod {
    private final int n;
    private final TimeUnit unit;
    // Count of the number of log statements skipped in the last period. See during post processing.
    private int skipCount = -1;

    private RateLimitPeriod(int n, TimeUnit unit) {
      // This code will work with a zero length time period, but it's nonsensical to try.
      if (n <= 0) {
        throw new IllegalArgumentException("time period must be positive: " + n);
      }
      this.n = n;
      this.unit = checkNotNull(unit, "time unit");
    }

    private long toNanos() {
      // Since nanoseconds are the smallest level of precision a TimeUnit can express, we are
      // guaranteed that "unit.toNanos(n) >= n > 0". This is important for correctness (see comment
      // in checkLastTimestamp()) because it ensures the new timestamp that indicates when logging
      // should occur always differs from the previous timestamp.
      return unit.toNanos(n);
    }

    private void setSkipCount(int skipCount) {
      this.skipCount = skipCount;
    }

    @Override
    public String toString() {
      // TODO: Make this less ugly and internationalization friendly.
      StringBuilder out = new StringBuilder()
          .append(n)
          .append(' ')
          .append(unit);
      if (skipCount > 0) {
          out.append(" [skipped: ").append(skipCount).append(']');
      }
      return out.toString();
    }

    @Override
    public int hashCode() {
      // Rough and ready. We don't expected this be be needed much at all.
      return (n * 37) ^ unit.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof RateLimitPeriod) {
        RateLimitPeriod that = (RateLimitPeriod) obj;
        return this.n == that.n && this.unit == that.unit;
      }
      return false;
    }
  }

  // Visible for testing.
  static final class StatsMap {
    // Use a concurrent map (rather than synchronization) because many threads could be contending
    // for log sites simultaneously.
    private final ConcurrentMap<Object, LogSiteStats> statsMap =
        new ConcurrentHashMap<Object, LogSiteStats>();

    /** Returns statistics for a given log site. */
    LogSiteStats getStatsForKey(Object logSiteKey) {
      LogSiteStats stats = statsMap.get(logSiteKey);
      if (stats == null) {
        stats = new LogSiteStats();
        LogSiteStats oldStats = statsMap.putIfAbsent(logSiteKey, stats);
        if (oldStats != null) {
          stats = oldStats;
        }
      }
      return stats;
    }
  }

  private static final StatsMap map = new StatsMap();

  public static LogSiteStats getStatsForKey(Object logSiteKey) {
    return map.getStatsForKey(logSiteKey);
  }

  private final AtomicLong invocationCount = new AtomicLong();
  private final AtomicLong lastTimestampNanos = new AtomicLong();
  private final AtomicInteger skippedLogStatements = new AtomicInteger();

  /**
   * Increments the invocation count and returns true if it was a multiple of the specified rate
   * limit count; implying that the log statement should be emitted. This is invoked during
   * post-processing if a rate limiting count was set via {@link LoggingApi#every(int)}.
   */
  boolean incrementAndCheckInvocationCount(int rateLimitCount) {
    return (invocationCount.getAndIncrement() % rateLimitCount) == 0;
  }

  /**
   * Checks whether the current time stamp is after the rate limiting period and if so, updates the
   * time stamp and returns true. This is invoked during post-processing if a rate limiting duration
   * was set via {@link LoggingApi#atMostEvery(int, TimeUnit)}.
   */
  boolean checkLastTimestamp(long timestampNanos, RateLimitPeriod period) {
    long lastNanos = lastTimestampNanos.get();
    // Avoid a race condition where two threads log at the same time. This is safe as lastNanos
    // can never be equal to timestampNanos (because the period is never zero), so if multiple
    // threads read the same value for lastNanos, only one thread can succeed in setting a new
    // value. For ludicrous durations which overflow the deadline we ensure it never triggers.
    long deadlineNanos = lastNanos + period.toNanos();
    if ((deadlineNanos >= 0)
        && (timestampNanos >= deadlineNanos || lastNanos == 0)
        && lastTimestampNanos.compareAndSet(lastNanos, timestampNanos)) {
      period.setSkipCount(skippedLogStatements.getAndSet(0));
      return true;
    } else {
      skippedLogStatements.incrementAndGet();
      return false;
    }
  }
}
