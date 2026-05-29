package com.example.domainhunter.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.domainhunter.data.db.AppDatabase
import com.example.domainhunter.data.model.Domain
import com.example.domainhunter.data.model.DomainStatus
import com.example.domainhunter.data.model.ScanSession
import com.example.domainhunter.data.model.SessionStatus
import com.example.domainhunter.ui.MainActivity
import com.example.domainhunter.utils.RdapFetcher
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class DomainScanService : Service() {

    private val client = OkHttpClient()
    private var scanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase

    private var currentSession: ScanSession? = null
    private var timeout = 5000L
    private var delay = 500L
    private var filePath = ""

    companion object {
        const val CHANNEL_ID = "domain_scan_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_TIMEOUT = "EXTRA_TIMEOUT"
        const val EXTRA_DELAY = "EXTRA_DELAY"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"

        var isRunning = false
        var isPaused = false
        var progress = 0
        var total = 0
        var registered = 0
        var failed = 0
        var ignored = 0
        var estimatedTimeLeft = ""
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopScan()
            return START_NOT_STICKY
        }

        timeout = intent?.getLongExtra(EXTRA_TIMEOUT, 5000L) ?: 5000L
        delay = intent?.getLongExtra(EXTRA_DELAY, 500L) ?: 500L
        filePath = intent?.getStringExtra(EXTRA_FILE_PATH) ?: ""
        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, -1L) ?: -1L

        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
        isPaused = false

        startScan(sessionId)
        return START_STICKY
    }

    private fun startScan(sessionId: Long) {
        scanJob = scope.launch {
            currentSession = if (sessionId != -1L) {
                db.sessionDao().getById(sessionId)
            } else null

            val lines = mutableListOf<String>()
            java.io.BufferedReader(java.io.FileReader(filePath)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.trim()?.lowercase()?.let {
                        if (it.endsWith(".com")) lines.add(it.removeSuffix(".com"))
                    }
                }
            }

            total = lines.size
            val startIndex = currentSession?.lastScannedIndex ?: 0

            if (currentSession == null) {
                val newSession = ScanSession(totalDomains = total)
                val id = db.sessionDao().insert(newSession)
                currentSession = db.sessionDao().getById(id)
            }

            val scanClient = OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .build()

            val startTime = System.currentTimeMillis()

            for (i in startIndex until lines.size) {
                while (isPaused) { delay(500) }
                if (!isRunning) break

                val name = lines[i]
                val netDomain = "$name.net"
                progress = i + 1

                try {
                    val request = Request.Builder()
                        .url("http://$netDomain")
                        .head()
                        .build()

                    val response = scanClient.newCall(request).execute()
                    response.close()

                    // النطاق محجوز — جلب RDAP
                    val rdap = RdapFetcher.fetch(netDomain)
                    val expiring = rdap?.expirationDate?.let {
                        isExpiringSoon(it)
                    } ?: false

                    db.domainDao().insert(
                        Domain(
                            sessionId = currentSession!!.id,
                            domainName = netDomain,
                            registrationDate = rdap?.registrationDate,
                            expirationDate = rdap?.expirationDate,
                            isExpiringSoon = expiring,
                            status = DomainStatus.REGISTERED
                        )
                    )
                    registered++

                } catch (e: Exception) {
                    // فشل الاتصال — نطاق متاح أو خطأ
                    if (e is java.net.ConnectException || e is java.net.UnknownHostException) {
                        ignored++
                    } else {
                        db.domainDao().insert(
                            Domain(
                                sessionId = currentSession!!.id,
                                domainName = netDomain,
                                registrationDate = null,
                                expirationDate = null,
                                status = DomainStatus.FAILED
                            )
                        )
                        failed++
                    }
                }

                // تحديث الجلسة
                db.sessionDao().update(
                    currentSession!!.copy(
                        scannedDomains = progress,
                        registeredCount = registered,
                        failedCount = failed,
                        lastScannedIndex = i
                    )
                )

                // حساب الوقت المتبقي
                val elapsed = System.currentTimeMillis() - startTime
                val avgPerItem = elapsed / progress.toDouble()
                val remaining = ((total - progress) * avgPerItem / 1000).toInt()
                estimatedTimeLeft = formatTime(remaining)

                updateNotification()
                delay(delay)
            }

            // انتهى الفحص
            db.sessionDao().update(
                currentSession!!.copy(
                    endTime = System.currentTimeMillis(),
                    status = SessionStatus.COMPLETED
                )
            )
            isRunning = false
            showCompletedNotification()
            stopSelf()
        }
    }

    private fun stopScan() {
        isRunning = false
        isPaused = false
        scanJob?.cancel()
        scope.launch {
            currentSession?.let {
                db.sessionDao().update(it.copy(status = SessionStatus.STOPPED))
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isExpiringSoon(expirationDate: String): Boolean {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val exp = sdf.parse(expirationDate) ?: return false
            val diff = exp.time - System.currentTimeMillis()
            diff < 30L * 24 * 60 * 60 * 1000
        } catch (e: Exception) { false }
    }

    private fun formatTime(seconds: Int): String {
        return when {
            seconds < 60 -> "$seconds ثانية"
            seconds < 3600 -> "${seconds / 60} دقيقة"
            else -> "${seconds / 3600} ساعة ${(seconds % 3600) / 60} دقيقة"
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DomainScanService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val percent = if (total > 0) (progress * 100 / total) else 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔍 Domain Hunter")
            .setContentText("تم فحص $progress / $total")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("✅ محجوزة: $registered  |  ❌ فاشلة: $failed  |  ⏭️ متجاهلة: $ignored\n📊 $progress / $total  ($percent%)\n⏱️ متبقي: $estimatedTimeLeft"))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setProgress(total, progress, false)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "إنهاء", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun showCompletedNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("✅ اكتمل الفحص!")
            .setContentText("وجدنا $registered نطاق .net محجوز")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Domain Scan", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "فحص النطاقات في الخلفية" }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scanJob?.cancel()
        scope.cancel()
    }
}
