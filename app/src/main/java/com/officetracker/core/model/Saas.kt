package com.officetracker.core.model

import java.util.concurrent.TimeUnit

enum class Billing(val label: String) {
    TRIAL("Trial"),
    MONTHLY("Monthly"),
    QUARTERLY("Quarterly"),
    YEARLY("Yearly"),
    CUSTOM("Custom"),
    LIFETIME("Lifetime");

    companion object {
        fun from(v: String?): Billing = entries.firstOrNull { it.name == v } ?: CUSTOM
    }
}

/** Switchable modules. The super admin sets them per plan and can override them per company. */
data class Features(
    val liveMap: Boolean = true,
    val places: Boolean = true,
    val reports: Boolean = true,
    val allowance: Boolean = true,
    val fakeGpsAlerts: Boolean = true,
    /** One account = one phone: signing in elsewhere signs the old phone out. */
    val singleDevice: Boolean = true,
    val scheduler: Boolean = true,
) {
    fun toMap(): Map<String, Boolean> = mapOf(
        "liveMap" to liveMap, "places" to places, "reports" to reports,
        "allowance" to allowance, "fakeGpsAlerts" to fakeGpsAlerts,
        "singleDevice" to singleDevice, "scheduler" to scheduler,
    )

    companion object {
        val ALL = Features()
        val LABELS = listOf(
            "liveMap" to "Live team map",
            "places" to "Known places",
            "reports" to "Report export (CSV)",
            "allowance" to "Travel allowance",
            "fakeGpsAlerts" to "Fake-GPS alerts",
            "singleDevice" to "One device per account",
            "scheduler" to "Auto start / end schedule",
        )

        fun fromMap(m: Map<*, *>?): Features {
            if (m == null) return ALL
            fun b(k: String) = (m[k] as? Boolean) ?: true
            return Features(
                b("liveMap"), b("places"), b("reports"), b("allowance"), b("fakeGpsAlerts"),
                // Added later: off for existing companies until the super admin switches it on,
                // so nobody is signed out unexpectedly by an app update.
                singleDevice = (m["singleDevice"] as? Boolean) ?: false,
                scheduler = b("scheduler"),
            )
        }
    }

    fun with(key: String, value: Boolean): Features = when (key) {
        "liveMap" -> copy(liveMap = value)
        "places" -> copy(places = value)
        "reports" -> copy(reports = value)
        "allowance" -> copy(allowance = value)
        "fakeGpsAlerts" -> copy(fakeGpsAlerts = value)
        "singleDevice" -> copy(singleDevice = value)
        "scheduler" -> copy(scheduler = value)
        else -> this
    }

    fun get(key: String): Boolean = toMap()[key] ?: true
}

/** A sellable package, defined by the super admin in plans/{id}. */
data class Plan(
    val id: String,
    val name: String,
    val description: String,
    val billing: Billing,
    val durationDays: Int,
    val price: Double,
    val maxUsers: Int,
    val maxAdmins: Int,
    val features: Features,
    val active: Boolean,
    val sortOrder: Int,
) {
    /** Price expressed per month, for revenue estimates. */
    val monthlyValue: Double
        get() = when (billing) {
            Billing.TRIAL, Billing.LIFETIME -> 0.0
            else -> if (durationDays > 0) price * 30.0 / durationDays else 0.0
        }
}

/** One customer (tenant). Everything the super admin controls lives on this document. */
data class Company(
    val id: String,
    val name: String,
    val contactName: String,
    val contactPhone: String,
    val email: String,
    val address: String,
    val notes: String,
    val planId: String?,
    val planName: String,
    val billing: Billing,
    val price: Double,
    /** Manual switch: SUSPENDED blocks the company immediately regardless of dates. */
    val suspended: Boolean,
    val suspendReason: String?,
    val startedAt: Long,
    val expiresAt: Long,
    /** expiresAt + grace period; after this the company is locked. */
    val accessUntil: Long,
    val maxUsers: Int,
    val maxAdmins: Int,
    val userCount: Int,
    val adminCount: Int,
    val features: Features,
    val settings: AppConfig,
    val createdAt: Long,
) {
    fun access(now: Long): AccessState = when {
        suspended -> AccessState.SUSPENDED
        now <= expiresAt -> if (billing == Billing.TRIAL) AccessState.TRIAL else AccessState.ACTIVE
        now <= accessUntil -> AccessState.GRACE
        else -> AccessState.EXPIRED
    }

    fun daysLeft(now: Long): Long =
        if (expiresAt <= now) 0 else (expiresAt - now + Subscription.DAY_MS - 1) / Subscription.DAY_MS

    val monthlyValue: Double
        get() = when (billing) {
            Billing.MONTHLY -> price
            Billing.QUARTERLY -> price / 3
            Billing.YEARLY -> price / 12
            else -> 0.0
        }

    val canAddUser: Boolean get() = userCount < maxUsers
}

enum class AccessState(val label: String, val usable: Boolean) {
    TRIAL("Trial", true),
    ACTIVE("Active", true),
    GRACE("Payment due", true),
    EXPIRED("Expired", false),
    SUSPENDED("Suspended", false),
}

/** Platform-wide switches, stored at platform/config and applied live on every phone. */
data class PlatformConfig(
    val appName: String = "Office Tracker",
    val graceDays: Int = 3,
    val supportPhone: String = "",
    val supportEmail: String = "",
    val announcement: String = "",
    val maintenanceMode: Boolean = false,
    val maintenanceMessage: String = "We are upgrading the service. Please try again shortly.",
    /** Phones below this versionCode must update before continuing. */
    val minVersionCode: Int = 0,
)

data class Payment(
    val id: String,
    val amount: Double,
    val method: String,
    val reference: String,
    val note: String,
    val extendedDays: Int,
    val createdAt: Long,
)

object Subscription {
    val DAY_MS: Long = TimeUnit.DAYS.toMillis(1)
    /** Far-future expiry used for LIFETIME plans (1 Jan 2100). */
    const val LIFETIME_EXPIRY = 4_102_444_800_000L

    /**
     * New expiry when a plan period is added. Renewing before expiry extends from the current
     * expiry, so the customer never loses paid days; after expiry it starts from today.
     */
    fun extend(currentExpiry: Long, days: Int, now: Long): Long {
        if (days <= 0) return currentExpiry
        val base = if (currentExpiry > now) currentExpiry else now
        return (base + days * DAY_MS).coerceAtMost(LIFETIME_EXPIRY)
    }

    fun accessUntil(expiresAt: Long, graceDays: Int): Long =
        if (expiresAt >= LIFETIME_EXPIRY) LIFETIME_EXPIRY else expiresAt + graceDays.coerceAtLeast(0) * DAY_MS
}

/** Plans created automatically when the platform is set up; the super admin can edit them. */
object StarterPlans {
    fun all(): List<Plan> = listOf(
        Plan("trial", "Free trial", "Try every feature", Billing.TRIAL, 14, 0.0, 5, 1, Features.ALL, true, 0),
        Plan("starter-monthly", "Starter", "Small teams, billed monthly", Billing.MONTHLY, 30, 1500.0, 10, 1,
            Features(fakeGpsAlerts = false, scheduler = false), true, 1),
        Plan("business-monthly", "Business", "Growing teams, billed monthly", Billing.MONTHLY, 30, 4000.0, 50, 3, Features.ALL, true, 2),
        Plan("business-yearly", "Business (yearly)", "Two months free", Billing.YEARLY, 365, 40000.0, 50, 3, Features.ALL, true, 3),
        Plan("enterprise-yearly", "Enterprise", "Large organisations", Billing.YEARLY, 365, 120000.0, 500, 10, Features.ALL, true, 4),
    )
}
