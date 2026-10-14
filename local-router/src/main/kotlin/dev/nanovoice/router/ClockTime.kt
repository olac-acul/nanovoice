package dev.nanovoice.router

/** A time of day on the 24-hour clock. */
data class ClockTime(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23 && minute in 0..59) { "invalid time $hour:$minute" }
    }
}
