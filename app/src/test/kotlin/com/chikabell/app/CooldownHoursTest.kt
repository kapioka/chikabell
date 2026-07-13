package com.chikabell.app

import com.chikabell.app.ui.locations.CooldownHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CooldownHoursTest {
    @Test
    fun formatsMinutesAsHours() {
        assertEquals("0", CooldownHours.format(0))
        assertEquals("0.5", CooldownHours.format(30))
        assertEquals("12", CooldownHours.format(720))
    }

    @Test
    fun parsesHalfHourSteps() {
        assertEquals(30L, CooldownHours.parse("0.5"))
        assertEquals(90L, CooldownHours.parse("1.5"))
        assertNull(CooldownHours.parse("1.25"))
        assertNull(CooldownHours.parse("720.5"))
    }
}
