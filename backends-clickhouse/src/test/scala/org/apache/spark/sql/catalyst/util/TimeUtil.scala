/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.catalyst.util

import java.time.{Instant, Period}
import java.util.{Calendar, Locale, TimeZone}
import java.util.concurrent.TimeUnit

import scala.util.matching.Regex

class TimeUtil {}

// scalastyle:off line.size.limit
object TimeUtil {
  private val ONE_MINUTE_TS = 60 * 1000L
  private val ONE_HOUR_TS = 60 * ONE_MINUTE_TS
  private val timeSuffixes = Map(
    "us" -> TimeUnit.MICROSECONDS,
    "ms" -> TimeUnit.MILLISECONDS,
    "s" -> TimeUnit.SECONDS,
    "m" -> TimeUnit.MINUTES,
    "min" -> TimeUnit.MINUTES,
    "h" -> TimeUnit.HOURS,
    "d" -> TimeUnit.DAYS
  )

  def getMinuteStart(ts: Long): Long = ts / ONE_MINUTE_TS * ONE_MINUTE_TS

  def getHourStart(ts: Long): Long = ts / ONE_HOUR_TS * ONE_HOUR_TS

  def getHour(ts: Long): Int = ((ts - getDayStart(ts)) / ONE_HOUR_TS).toInt

  def getDayStart(ts: Long): Long = {
    val zoneId = TimeZone.getDefault.toZoneId
    val localDate = Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate
    localDate.atStartOfDay.atZone(zoneId).toInstant.toEpochMilli
  }

  def getWeekStart(ts: Long): Long = {
    val calendar = Calendar.getInstance(TimeZone.getDefault, Locale.getDefault)
    calendar.setTimeInMillis(getDayStart(ts))
    calendar.add(
      Calendar.DAY_OF_WEEK,
      calendar.getFirstDayOfWeek - calendar.get(Calendar.DAY_OF_WEEK))
    calendar.getTimeInMillis
  }

  def getMonthStart(ts: Long): Long = {
    val calendar = Calendar.getInstance(TimeZone.getDefault, Locale.getDefault)
    calendar.setTimeInMillis(ts)
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH)
    calendar.clear()
    calendar.set(year, month, 1)
    calendar.getTimeInMillis
  }

  def getQuarterStart(ts: Long): Long = {
    val calendar = Calendar.getInstance(TimeZone.getDefault, Locale.getDefault)
    calendar.setTimeInMillis(ts)
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH)
    calendar.clear()
    calendar.set(year, month / 3 * 3, 1)
    calendar.getTimeInMillis
  }

  def getYearStart(ts: Long): Long = {
    val calendar = Calendar.getInstance(TimeZone.getDefault, Locale.getDefault)
    calendar.setTimeInMillis(ts)
    val year = calendar.get(Calendar.YEAR)
    calendar.clear()
    calendar.set(year, 0, 1)
    calendar.getTimeInMillis
  }

  def timeStringAs(str: String, unit: TimeUnit): Long = {
    val lower = str.toLowerCase(Locale.ROOT).trim
    val regex: Regex = """(-?[0-9]+)([a-z]+)?""".r
    regex.findFirstMatchIn(lower) match {
      case Some(m) =>
        val value = m.group(1).toLong
        val suffix = m.group(2)
        if (suffix != null && !timeSuffixes.contains(suffix)) {
          throw new NumberFormatException("Invalid suffix: \"" + suffix + "\"")
        }
        (suffix match {
          case null => unit
          case s => timeSuffixes(s)
        }).convert(value, unit)
      case None =>
        throw new NumberFormatException(
          "Time must be specified as seconds (s), milliseconds (ms), microseconds (us), minutes (m or min), hour (h)," +
            " or day (d). E.g. 50s, 100ms, or 250us."
        )
    }
  }

  def minusDays(ts: Long, days: Int): Long = {
    val zoneId = TimeZone.getDefault.toZoneId
    val zonedDateTime = Instant.ofEpochMilli(ts).atZone(zoneId)
    zonedDateTime.minusDays(days).toInstant.toEpochMilli
  }

  def ymdintBetween(timestamp1: Long, timestamp2: Long): String = {
    val zoneId = TimeZone.getDefault.toZoneId
    val date1 = Instant.ofEpochMilli(timestamp1).atZone(zoneId).toLocalDate
    val date2 = Instant.ofEpochMilli(timestamp2).atZone(zoneId).toLocalDate
    val between = Period.between(date1, date2)
    f"${Math.abs(between.getYears)}%d${Math.abs(between.getMonths)}%02d${Math.abs(between.getDays)}%02d"
  }
}
// scalastyle:on line.size.limit
