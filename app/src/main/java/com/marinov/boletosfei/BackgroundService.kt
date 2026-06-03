package com.marinov.boletosfei

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BackgroundService : Service() {

    companion object {
        private const val TAG = "BackgroundService"

        // ── Notificação persistente (foreground) ──────────────────────────────
        private const val FOREGROUND_CHANNEL_ID   = "background_service_channel"
        private const val FOREGROUND_CHANNEL_NAME = "Serviço em segundo plano"
        const val FOREGROUND_NOTIFICATION_ID      = 9001

        // ── Notificação temporária de sync ────────────────────────────────────
        private const val SYNC_CHANNEL_ID   = "sync_updates_channel"
        private const val SYNC_CHANNEL_NAME = "Atualizações em segundo plano"
        private const val SYNC_ID_LOGIN     = 9010
        private const val SYNC_ID_BOLETOS   = 9011
        private const val SYNC_ID_UPDATE    = 9014

        // ── Notificações de conteúdo (mantidos dos workers) ───────────────────
        private const val NOTIF_ID_BOLETOS  = 3001
        private const val NOTIF_ID_UPDATE   = 1001

        // ── Intervalos (ms) — mesmos dos workers originais ───────────────────
        private const val LOGIN_INTERVAL_MS   = 15L  * 60 * 1000
        private const val BOLETOS_INTERVAL_MS = 20L  * 60 * 1000
        private const val UPDATE_INTERVAL_MS  = 120L * 60 * 1000

        fun start(context: Context) {
            val intent = Intent(context, BackgroundService::class.java)
            context.startForegroundService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao chamar startForeground()", e)
        }

        Dados.init(applicationContext)
        startPeriodicTasks()
        Log.d(TAG, "Serviço em segundo plano iniciado.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Serviço em segundo plano encerrado.")
    }

    private fun startPeriodicTasks() {
        serviceScope.launch {
            while (isActive) {
                delay(LOGIN_INTERVAL_MS)
                runWithSyncNotification(SYNC_ID_LOGIN) { runLoginLogic() }
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(BOLETOS_INTERVAL_MS)
                runWithSyncNotification(SYNC_ID_BOLETOS) { runBoletosLogic() }
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(UPDATE_INTERVAL_MS)
                runWithSyncNotification(SYNC_ID_UPDATE) { runUpdateCheckLogic() }
            }
        }
    }

    private suspend fun runWithSyncNotification(notificationId: Int, block: suspend () -> Unit) {
        showSyncNotification(notificationId)
        try {
            block()
        } finally {
            cancelNotification(notificationId)
        }
    }

    // ── Lógicas extraídas dos workers ────────────────────────────────────────

    private suspend fun runLoginLogic() {
        val prefs = LoginActivity.getEncryptedPrefs(applicationContext)
        val user  = prefs.getString(LoginActivity.KEY_USER, "")
        val pass  = prefs.getString(LoginActivity.KEY_PASS, "")

        if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
            Log.w(TAG, "LoginLogic: credenciais ausentes — marcando como deslogado.")
            prefs.edit { putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false) }
            return
        }
        try {
            val result = LoginLogic.performLogin(user, pass, applicationContext)
            if (result.success) {
                Log.d(TAG, "LoginLogic: sessão renovada com sucesso.")
            } else {
                Log.e(TAG, "LoginLogic: falha → ${result.errorMessage}")
                if (result.errorMessage.contains("incorretos", ignoreCase = true) ||
                    result.errorMessage.contains("senha", ignoreCase = true)) {
                    prefs.edit { putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LoginLogic: exceção inesperada", e)
            prefs.edit { putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false) }
        }
    }

    private suspend fun runBoletosLogic() {
        try {
            val houve = Dados.atualizaBoletos()
            if (houve) {
                Log.d(TAG, "BoletosLogic: alteração detectada.")
                sendBoletosNotification()
            } else {
                Log.d(TAG, "BoletosLogic: nenhuma alteração.")
            }
        } catch (_: SessionExpiredException) {
            Log.w(TAG, "BoletosLogic: sessão expirada → renovando login...")
            runLoginLogic()
        } catch (e: Exception) {
            Log.e(TAG, "BoletosLogic: erro", e)
        }
    }

    private suspend fun runUpdateCheckLogic() {
        try {
            val deferred = CompletableDeferred<Unit>()
            UpdateChecker.checkForUpdate(
                applicationContext,
                isManualCheck = false,
                listener = object : UpdateChecker.UpdateListener {
                    override fun onUpdateAvailable(url: String, version: String, releaseNotes: String) {
                        sendUpdateNotification(version)
                        deferred.complete(Unit)
                    }
                    override fun onUpToDate() { deferred.complete(Unit) }
                    override fun onError(message: String) {
                        Log.e(TAG, "UpdateLogic: erro → $message")
                        deferred.complete(Unit)
                    }
                }
            )
            deferred.await()
        } catch (e: Exception) {
            Log.e(TAG, "UpdateLogic: exceção", e)
        }
    }

    // ── Canais e notificações ────────────────────────────────────────────────

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(FOREGROUND_CHANNEL_ID, FOREGROUND_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(SYNC_CHANNEL_ID, SYNC_CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel("boletos_channel", "Atualizações de Boletos", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel("update_channel", "Atualizações do software", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service)
            .setContentTitle("Serviço em segundo plano")
            .setContentText("O serviço necessário para atualizações em segundo plano está ativo")
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun showSyncNotification(notificationId: Int) {
        if (!hasNotificationPermission()) return
        val notification = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("Sincronizando dados")
            .setContentText("Baixando dados...")
            .setSilent(true)
            .setOngoing(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificação de sync #$notificationId", e)
        }
    }

    private fun cancelNotification(notificationId: Int) {
        try {
            NotificationManagerCompat.from(this).cancel(notificationId)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao cancelar notificação #$notificationId", e)
        }
    }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun sendBoletosNotification() {
        if (!hasNotificationPermission()) return
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("destination", "boletos")
        }
        val pi = PendingIntent.getActivity(applicationContext, 300, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, "boletos_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Boleto atualizado!")
            .setContentText("Houve alteração nos seus boletos. Toque para ver.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Há novidades nos seus boletos da FEI. Confira se há algum boleto em aberto ou recém-pago."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID_BOLETOS, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificação de boletos", e)
        }
    }

    private fun sendUpdateNotification(version: String) {
        if (!hasNotificationPermission()) return
        val intent = Intent(applicationContext, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_update_directly", true)
        }
        val pi = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, "update_channel")
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("Atualização Disponível (v$version)")
            .setContentText("Clique aqui para baixar a nova versão do aplicativo.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID_UPDATE, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para notificação de atualização", e)
        }
    }
}