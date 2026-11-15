package de.doorzign.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.doorzign.app.AppContainer
import de.doorzign.app.BuildConfig
import de.doorzign.app.R
import de.doorzign.app.ui.common.Texts
import de.doorzign.app.api.model.SystemInfo
import de.doorzign.app.core.AddressInput
import de.doorzign.app.core.ApiException
import de.doorzign.app.core.Compatibility
import de.doorzign.app.core.ConnectionProfile
import de.doorzign.app.core.PairingUri
import de.doorzign.app.core.ProbeResult
import de.doorzign.app.core.SystemProbe
import de.doorzign.app.core.SystemType
import de.doorzign.app.core.Trust
import de.doorzign.app.core.Versions
import de.doorzign.app.net.FoundGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Kandidat für Trust on first use: Server ist erreichbar, Fingerprint muss bestätigt werden. */
data class TofuCandidate(val probe: ProbeResult, val fingerprint: String, val warning: String?)

data class OnboardingState(
    val busy: Boolean = false,
    val error: String? = null,
    val tofu: TofuCandidate? = null,
)

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    val mockAvailable: Boolean = BuildConfig.DEBUG && BuildConfig.MOCK_ORIGIN.isNotEmpty()

    fun clearError() = _state.update { it.copy(error = null) }

    /** QR-Inhalt (Kamera oder Deep Link). */
    fun onQr(raw: String) {
        val p = runCatching { PairingUri.parse(raw) }.getOrElse { _state.update { s -> s.copy(error = Texts.error(it)) }; return }
        onPairing(p)
    }

    fun onPairing(p: PairingUri) = when (p) {
        is PairingUri.Edge -> connectEdgeQr(p)
        is PairingUri.Cloud -> connect(p.origin, Trust.System)
    }

    private fun connectEdgeQr(p: PairingUri.Edge) = run {
        val r = SystemProbe.probeEdge(p, c.appInfo)
        val profile = ConnectionProfile(SystemType.EDGE, r.system.instanceId, r.system.name, p.origins, p.fingerprints).preferring(r.origin)
        activate(profile, r.system)
    }

    /** Edge-Adresse von Hand oder aus der mDNS-Suche → Fingerprint bestätigen lassen. */
    fun probeEdgeAddress(input: String, announced: FoundGateway? = null) = run {
        val origin = announced?.origin ?: AddressInput.edgeOrigin(input)
        val r = SystemProbe.probe(origin, Trust.Capture(), c.appInfo)
        val fp = r.observedFingerprint ?: throw ApiException(0, ApiException.TLS)
        val warning = when {
            announced?.fingerprint != null && announced.fingerprint != fp -> Texts.get(R.string.tofu_warn_fingerprint)
            announced?.instanceId != null && announced.instanceId != r.system.instanceId -> Texts.get(R.string.tofu_warn_instance)
            else -> null
        }
        _state.update { it.copy(tofu = TofuCandidate(r, fp, warning)) }
    }

    fun confirmTofu() {
        val t = _state.value.tofu ?: return
        run {
            val profile = ConnectionProfile(SystemType.EDGE, t.probe.system.instanceId, t.probe.system.name,
                listOf(t.probe.origin), listOf(t.fingerprint))
            activate(profile, t.probe.system)
        }
    }

    fun cancelTofu() = _state.update { it.copy(tofu = null) }

    fun connectCloud(input: String) {
        val origin = runCatching { AddressInput.cloudOrigin(input) }.getOrElse { _state.update { s -> s.copy(error = Texts.error(it)) }; return }
        connect(origin, Trust.System)
    }

    /** Nur Debug: Prism-Mock ohne TLS und ohne `/mobile/v1`-Präfix. */
    fun connectMock() = run {
        val r = SystemProbe.probe(BuildConfig.MOCK_ORIGIN, Trust.System, c.appInfo, apiPath = "")
        activate(ConnectionProfile(type(r.system), r.system.instanceId, r.system.name, listOf(BuildConfig.MOCK_ORIGIN), apiPath = ""), r.system)
    }

    private fun connect(origin: String, trust: Trust) = run {
        val r = SystemProbe.probe(origin, trust, c.appInfo)
        activate(ConnectionProfile(type(r.system), r.system.instanceId, r.system.name, listOf(origin)), r.system)
    }

    private fun type(s: SystemInfo) = if (s.systemType == SystemInfo.SystemType.EDGE) SystemType.EDGE else SystemType.CLOUD

    private fun activate(profile: ConnectionProfile, system: SystemInfo) {
        when (Versions.check(system.apiVersion, system.minAppVersion, c.appInfo.version)) {
            Compatibility.UPDATE_APP -> throw ApiException(0, ApiException.UPDATE_APP)
            Compatibility.INCOMPATIBLE -> throw ApiException(0, ApiException.INCOMPATIBLE)
            Compatibility.OK -> Unit
        }
        c.sessions.activate(profile, system)
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(busy = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = Texts.error(e)) }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(busy = false, error = Texts.error(e)) }
            }
        }
    }
}
