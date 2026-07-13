package com.chikabell.app.ui.locations

object CooldownHours {
    fun format(minutes: Long): String = when {
        minutes % 60L == 0L -> (minutes / 60L).toString()
        minutes % 30L == 0L -> "${minutes / 60L}.5"
        else -> minutes.div(60.0).toString()
    }

    fun parse(value: String): Long? {
        val hours = value.toDoubleOrNull() ?: return null
        if (!hours.isFinite() || hours < 0.0) return null
        val halfHours = hours * 2.0
        if (halfHours % 1.0 != 0.0) return null
        val minutes = halfHours.toLong() * 30L
        return minutes.takeIf { it <= 43_200L }
    }
}
