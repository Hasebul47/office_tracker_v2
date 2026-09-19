package com.officetracker

import com.officetracker.core.model.AccessState
import com.officetracker.core.model.AppConfig
import com.officetracker.core.model.Billing
import com.officetracker.core.model.Company
import com.officetracker.core.model.Features
import com.officetracker.core.model.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionTest {
    private val day = Subscription.DAY_MS
    private val now = 1_800_000_000_000L

    private fun company(expires: Long, graceDays: Int = 3, billing: Billing = Billing.MONTHLY, suspended: Boolean = false) = Company(
        id = "c", name = "Acme", contactName = "", contactPhone = "", email = "", address = "", notes = "",
        planId = "p", planName = "Plan", billing = billing, price = 3000.0, suspended = suspended, suspendReason = null,
        startedAt = now - 10 * day, expiresAt = expires, accessUntil = Subscription.accessUntil(expires, graceDays),
        maxUsers = 10, maxAdmins = 2, userCount = 10, adminCount = 1, features = Features.ALL, settings = AppConfig(), createdAt = 0,
    )

    @Test
    fun accessStates() {
        assertEquals(AccessState.ACTIVE, company(now + day).access(now))
        assertEquals(AccessState.TRIAL, company(now + day, billing = Billing.TRIAL).access(now))
        assertEquals(AccessState.GRACE, company(now - day).access(now))
        assertEquals(AccessState.EXPIRED, company(now - 4 * day).access(now))
        assertEquals(AccessState.SUSPENDED, company(now + 100 * day, suspended = true).access(now))
        assertTrue(AccessState.GRACE.usable)
        assertFalse(AccessState.EXPIRED.usable)
    }

    @Test
    fun renewalBeforeExpiryKeepsRemainingDays() {
        val expires = now + 5 * day
        assertEquals(expires + 30 * day, Subscription.extend(expires, 30, now))
    }

    @Test
    fun renewalAfterExpiryStartsToday() {
        assertEquals(now + 30 * day, Subscription.extend(now - 20 * day, 30, now))
    }

    @Test
    fun lifetimeHasNoGrace() {
        assertEquals(Subscription.LIFETIME_EXPIRY, Subscription.accessUntil(Subscription.LIFETIME_EXPIRY, 3))
    }

    @Test
    fun daysLeftRoundsUp() {
        assertEquals(2L, company(now + day + 1).daysLeft(now))
        assertEquals(0L, company(now - 1).daysLeft(now))
    }

    @Test
    fun seatLimit() {
        assertFalse(company(now + day).canAddUser)
    }

    @Test
    fun monthlyValueNormalisesBilling() {
        assertEquals(250.0, company(now + day, billing = Billing.YEARLY).monthlyValue, 0.001)
        assertEquals(0.0, company(now + day, billing = Billing.TRIAL).monthlyValue, 0.001)
    }

    @Test
    fun featureToggleRoundTrip() {
        val f = Features.ALL.with("places", false)
        assertFalse(f.places)
        assertEquals(f, Features.fromMap(f.toMap()))
    }
}
