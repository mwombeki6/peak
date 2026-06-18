package com.mwombeki.peak.shared.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Date and Time Utility Engine.
 * Forces consistent UTC time zone handling across the entire monolith application.
 */

object DateTimeUtils {
    //default system timezones to handle hotel local operations cleanly
    val EAST_AFRICA_ZONE: ZoneId = ZoneId.of("Africa/Dar es salaam")

    private val ISO_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Converts a standard system Instant to the local operating date of the hotel.
     */
    fun toLocalDate(instant: Instant): LocalDate {
        return instant.atZone(EAST_AFRICA_ZONE).toLocalDate()
    }

    /**
     * Converts a local date string (YYYY-MM-DD) safely to a UTC Instant at start of day.
     */
    fun parseToStartOfDayInstant(dateStr: String): Instant {
        val localDate = LocalDate.parse(dateStr, ISO_DATE_FORMATTER)
        return localDate.atStartOfDay(EAST_AFRICA_ZONE).toInstant()
    }

    /**
     * Calculates the absolute number of overnight stays between a check-in and check-out date.
     * Essential for strict room rate calculations.
     */
    fun calculateNightsBetween(checkIn: Instant, checkout: Instant): Long{
        val checkInDate = toLocalDate(checkIn)
        val checkoutDate = toLocalDate(checkout)

        val nights = ChronoUnit.DAYS.between(checkInDate, checkoutDate)
        if(nights <= 0){
            // Day-use bookings are charged as one night.
            return 1
        }
        return nights
    }

}
