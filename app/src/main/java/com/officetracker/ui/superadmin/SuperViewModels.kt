package com.officetracker.ui.superadmin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.officetracker.AppContainer
import com.officetracker.core.model.AccessState
import com.officetracker.core.model.Company
import com.officetracker.core.model.Payment
import com.officetracker.core.model.Plan
import com.officetracker.core.model.PlatformConfig
import com.officetracker.core.model.Role
import com.officetracker.core.model.UserProfile
import com.officetracker.data.repo.LegacyImportReport
import com.officetracker.data.repo.NewCompanyInput
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Shared busy/message handling for super admin screens. */
abstract class ActionViewModel : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    protected fun launchResult(success: String? = null, onDone: () -> Unit = {}, block: suspend () -> Result<*>) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
                    .onSuccess { success?.let { m -> message = m }; onDone() }
                    .onFailure { message = it.message ?: "Something went wrong." }
            } finally {
                busy = false
            }
        }
    }
}

/** Companies and plans, live; used by the dashboard and the companies list. */
class PlatformOverviewViewModel(private val c: AppContainer) : ActionViewModel() {
    val companies: StateFlow<List<Company>?> = c.platform.observeCompanies()
        .catch { message = it.message; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val plans: StateFlow<List<Plan>> = c.platform.observePlans()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createCompany(input: NewCompanyInput, plan: Plan, onDone: (Company) -> Unit) {
        if (busy) return
        launchResult(success = null) {
            c.platform.createCompany(input, plan).onSuccess { created ->
                message = "${created.name} created. ${input.adminName} can sign in with ${input.adminPhone}."
                onDone(created)
            }
        }
    }
}

data class DashboardStats(
    val companies: Int,
    val trial: Int,
    val active: Int,
    val grace: Int,
    val expired: Int,
    val suspended: Int,
    val users: Int,
    val mrr: Double,
    val expiringSoon: List<Company>,
    val needsAttention: List<Company>,
    val byPlan: List<Pair<String, Int>>,
) {
    companion object {
        fun from(companies: List<Company>, now: Long): DashboardStats {
            val states = companies.associateWith { it.access(now) }
            val week = 7L * 86_400_000L
            return DashboardStats(
                companies = companies.size,
                trial = states.count { it.value == AccessState.TRIAL },
                active = states.count { it.value == AccessState.ACTIVE },
                grace = states.count { it.value == AccessState.GRACE },
                expired = states.count { it.value == AccessState.EXPIRED },
                suspended = states.count { it.value == AccessState.SUSPENDED },
                users = companies.sumOf { it.userCount },
                mrr = companies.filter { states[it] == AccessState.ACTIVE || states[it] == AccessState.GRACE }.sumOf { it.monthlyValue },
                expiringSoon = companies.filter {
                    val s = states[it]
                    (s == AccessState.TRIAL || s == AccessState.ACTIVE) && it.expiresAt - now <= week
                }.sortedBy { it.expiresAt },
                needsAttention = companies.filter {
                    val s = states[it]
                    s == AccessState.GRACE || s == AccessState.EXPIRED
                }.sortedBy { it.expiresAt },
                byPlan = companies.groupingBy { it.planName.ifBlank { "No plan" } }.eachCount().toList().sortedByDescending { it.second },
            )
        }
    }
}

class CompanyDetailViewModel(private val c: AppContainer, val companyId: String) : ActionViewModel() {
    val company: StateFlow<Company?> = c.platform.observeCompany(companyId)
        .catch { message = it.message; emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val people: StateFlow<List<UserProfile>> = c.users.observeUsers(companyId)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val payments: StateFlow<List<Payment>> = c.org.observePayments(companyId)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val plans: StateFlow<List<Plan>> = c.platform.observePlans()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var importReport by mutableStateOf<LegacyImportReport?>(null)

    private fun current(): Company? = company.value

    fun assignPlan(plan: Plan, renew: Boolean, onDone: () -> Unit) {
        val co = current() ?: return
        launchResult("Plan changed to ${plan.name}.", onDone) { c.platform.assignPlan(co, plan, renew) }
    }

    fun extend(days: Int) {
        val co = current() ?: return
        launchResult("Extended by $days days.") { c.platform.extend(co, days) }
    }

    fun setExpiry(millis: Long) {
        val co = current() ?: return
        launchResult("Expiry date updated.") { c.platform.updateSubscription(co.copy(expiresAt = millis)) }
    }

    fun setSuspended(suspended: Boolean, reason: String?) {
        val co = current() ?: return
        launchResult(if (suspended) "Company suspended. All its phones are locked now." else "Company re-activated.") {
            c.platform.setSuspended(co, suspended, reason)
        }
    }

    fun saveSubscription(updated: Company, onDone: () -> Unit = {}) =
        launchResult("Saved. Changes are live on the company's phones.", onDone) { c.platform.updateSubscription(updated) }

    fun saveProfile(updated: Company, onDone: () -> Unit) =
        launchResult("Company details saved.", onDone) { c.platform.updateCompanyProfile(updated) }

    fun recordPayment(payment: Payment, onDone: () -> Unit) {
        val co = current() ?: return
        launchResult("Payment recorded.", onDone) { c.platform.recordPayment(co, payment) }
    }

    fun addPerson(name: String, phone: String, password: String, role: Role, onDone: () -> Unit) =
        launchResult("$name added.", onDone) {
            // The super admin may exceed plan limits deliberately (e.g. a goodwill seat).
            c.users.createUser(companyId, name, phone, password, department = "", role = role)
        }

    fun recount() = launchResult("Counters recalculated.") { runCatching { c.users.recount(companyId) } }

    fun importLegacy() {
        launchResult(null) { c.users.importLegacyUsers(companyId).onSuccess { importReport = it } }
    }

    fun delete(onDone: () -> Unit) = launchResult(null, onDone) { c.platform.deleteCompany(companyId) }
}

class PlansViewModel(private val c: AppContainer) : ActionViewModel() {
    val plans: StateFlow<List<Plan>?> = c.platform.observePlans()
        .catch { message = it.message; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(plan: Plan, onDone: () -> Unit) = launchResult("Plan saved.", onDone) { c.platform.savePlan(plan) }

    fun delete(plan: Plan) = launchResult("Plan deleted. Companies already on it keep their terms.") { c.platform.deletePlan(plan.id) }
}

class PlatformSettingsViewModel(private val c: AppContainer) : ActionViewModel() {
    val config: StateFlow<PlatformConfig> = c.platform.platform

    fun save(config: PlatformConfig) = launchResult("Platform settings saved. Every phone has them now.") { c.platform.savePlatform(config) }

    fun changePassword(current: String, new: String, onDone: () -> Unit) =
        launchResult("Password changed.", onDone) { c.auth.changePassword(current, new) }

    fun signOut() = c.auth.signOut()
}
