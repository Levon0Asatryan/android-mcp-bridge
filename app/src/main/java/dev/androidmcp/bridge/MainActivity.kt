package dev.androidmcp.bridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModelProvider
import dev.androidmcp.bridge.gateway.GatewayService
import dev.androidmcp.bridge.health.HealthConnectManager
import dev.androidmcp.bridge.ui.BridgeUi
import dev.androidmcp.bridge.ui.UiModel

class MainActivity : ComponentActivity() {
    private lateinit var model: UiModel
    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { model.refresh() }
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var pendingCredential: ((Boolean) -> Unit)? = null
    private var biometricCancellation: CancellationSignal? = null
    private val deviceCredentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingCredential?.invoke(result.resultCode == Activity.RESULT_OK)
        pendingCredential = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model = ViewModelProvider(this)[UiModel::class.java]
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(model::acceptSharedText)
        }
        setContent {
            BridgeUi(model, approve = ::confirmHealthOperation, pair = ::confirmPairing, approveLink = ::confirmLink,
                permissions = { permissionLauncher.launch(HealthConnectManager.permissions) },
                manageHealth = { model.run { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } },
                start = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    model.run { startForegroundService(Intent(this, GatewayService::class.java)) }
                },
                stop = { model.run { startService(Intent(this, GatewayService::class.java).setAction("STOP")) } })
        }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.refresh()
    }

    private fun confirmHealthOperation(id: String) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure || keyguard.isDeviceLocked) {
            model.app.phoneApproval.respond(id, false)
            model.showError("Set up and unlock your phone's PIN, password or pattern before approving a deletion.")
            return
        }
        authenticate("Confirm deletion", "Approve deleting a Android MCP Bridge record") { model.app.phoneApproval.respond(id, it) }
    }

    private fun confirmLink(linkId: String, scopes: Set<String>) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val deny = { model.app.linkApproval.respond(linkId, dev.androidmcp.bridge.security.LinkDecision(false)) }
        if (!keyguard.isDeviceSecure || keyguard.isDeviceLocked) {
            deny()
            model.showError("Set up and unlock your phone's PIN, password or pattern before approving a connection.")
            return
        }
        authenticate("Approve connection", "Allow the selected access to your health data") { approved ->
            model.app.linkApproval.respond(linkId, dev.androidmcp.bridge.security.LinkDecision(approved, if (approved) scopes else emptySet()))
        }
    }

    private fun confirmPairing(origin: String, secret: String) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            model.showError("Set a PIN, password or pattern screen lock before pairing.")
            return
        }
        val bootstrap = secret.toCharArray()
        authenticate("Pair this phone", "Connect Android MCP Bridge to your private instance") { approved ->
            if (approved) model.run { model.app.pair(origin, bootstrap) } else bootstrap.fill('\u0000')
        }
    }

    private fun authenticate(title: String, subtitle: String, result: (Boolean) -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            biometricCancellation?.cancel()
            val cancellation = CancellationSignal()
            biometricCancellation = cancellation
            BiometricPrompt.Builder(this)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build().authenticate(cancellation, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(authResult: BiometricPrompt.AuthenticationResult) {
                        biometricCancellation = null
                        result(true)
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        biometricCancellation = null
                        android.util.Log.w("HB", "auth error $errorCode $errString")
                        result(false)
                    }
                })
        } else {
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) {
                result(false)
                model.showError("Device authentication is unavailable. Nothing was approved.")
            } else {
                pendingCredential = result
                deviceCredentialLauncher.launch(intent)
            }
        }
    }

    override fun onDestroy() {
        biometricCancellation?.cancel()
        pendingCredential?.invoke(false)
        pendingCredential = null
        super.onDestroy()
    }
}
