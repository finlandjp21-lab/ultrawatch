package com.harshvardhan.ultrawatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    @Test fun unsetLockAlwaysOpens() {
        assertEquals(AppLock.Result.Ok, AppLock().verify("0000", 0))
    }

    @Test fun correctAndWrongPin() {
        val l = AppLock(); l.setPin("2468")
        assertEquals(AppLock.Result.Ok, l.verify("2468", 0))
        assertEquals(AppLock.Result.Wrong(4), l.verify("1111", 0))
        assertEquals(AppLock.Result.Ok, l.verify("2468", 0)) // resets counter
        assertEquals(AppLock.Result.Wrong(4), l.verify("1111", 0))
    }

    @Test fun lockoutDoublesAndExpires() {
        val l = AppLock(); l.setPin("1234")
        repeat(4) { l.verify("0000", 0) }
        assertEquals(AppLock.Result.LockedOut(30_000), l.verify("0000", 0))
        assertTrue(l.verify("1234", 10_000) is AppLock.Result.LockedOut) // even right PIN waits
        assertEquals(AppLock.Result.LockedOut(60_000), l.verify("0000", 30_000))
        assertEquals(AppLock.Result.Ok, l.verify("1234", 90_000))
    }

    @Test fun persistsWithoutStoringPin() {
        val l = AppLock(); l.setPin("987654")
        val enc = l.encode()
        assertFalse(enc.contains("987654"))
        assertEquals(AppLock.Result.Ok, AppLock.decode(enc).verify("987654", 0))
        assertFalse(AppLock.decode("junk").isSet)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsBadPin() {
        AppLock().setPin("12a4")
    }
}
