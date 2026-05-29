package com.example.domainhunter.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.domainhunter.databinding.ActivityMainBinding
import com.example.domainhunter.service.DomainScanService
import com.example.domainhunter.utils.ExportHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val adapter = DomainAdapter()
    private var filePath: String? = null
    private var totalDomainsInFile = 0
    private var uiUpdateJob: Job? = null

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val file = File(cacheDir, "domains.txt")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            filePath = file.absolutePath
            countDomainsInFile(file)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // طلب إذن الإشعارات
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        viewModel.domains.observe(this) { list ->
            adapter.submitList(list)
            if (list.isNotEmpty()) {
                binding.recyclerView.scrollToPosition(0)
            }
        }

        binding.btnImport.setOnClickListener { filePicker.launch("text/*") }

        binding.btnStart.setOnClickListener {
            if (filePath == null) {
                Toast.makeText(this, "اختر ملفاً أولاً!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (DomainScanService.isPaused) {
                DomainScanService.isPaused = false
                binding.btnPause.text = "⏸️ إيقاف مؤقت"
                return@setOnClickListener
            }
            startScan()
        }

        binding.btnPause.setOnClickListener {
            val intent = Intent(this, DomainScanService::class.java).apply {
                action = DomainScanService.ACTION_PAUSE
            }
            startService(intent)
            DomainScanService.isPaused = !DomainScanService.isPaused
            binding.btnPause.text = if (DomainScanService.isPaused) "▶️ استئناف" else "⏸️ إيقاف مؤقت"
        }

        binding.btnStop.setOnClickListener { stopScan() }

        binding.etSearch.addTextChangedListener { viewModel.setSearch(it.toString()) }

        binding.btnExport.setOnClickListener {
            val domains = adapter.currentList
            if (domains.isEmpty()) { Toast.makeText(this, "لا توجد نتائج!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startActivity(Intent.createChooser(ExportHelper.exportToCsv(this, domains), "تصدير النتائج"))
        }

        binding.btnCopyAll.setOnClickListener {
            val domains = adapter.currentList
            if (domains.isEmpty()) { Toast.makeText(this, "لا توجد نتائج!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("domains", ExportHelper.copyAll(domains)))
            Toast.makeText(this, "تم نسخ ${domains.size} نطاق!", Toast.LENGTH_SHORT).show()
        }

        startUiUpdate()
    }

    private fun countDomainsInFile(file: File) {
        CoroutineScope(Dispatchers.IO).launch {
            var count = 0
            file.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.trim().lowercase().endsWith(".com")) count++
                }
            }
            totalDomainsInFile = count
            withContext(Dispatchers.Main) {
                binding.tvFileName.text = "✅ ${file.name}  |  📊 $count نطاق .com"
                binding.tvProgress.text = "0 / $count"
                binding.progressBar.max = count
            }
        }
    }

    private fun startScan() {
        val intent = Intent(this, DomainScanService::class.java).apply {
            putExtra(DomainScanService.EXTRA_FILE_PATH, filePath)
            putExtra(DomainScanService.EXTRA_TIMEOUT, binding.etTimeout.text.toString().toLongOrNull() ?: 5000L)
            putExtra(DomainScanService.EXTRA_DELAY, binding.etDelay.text.toString().toLongOrNull() ?: 500L)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        if (DomainScanService.currentSessionId != -1L) {
            viewModel.setSession(DomainScanService.currentSessionId)
        }

        binding.btnStart.isEnabled = false
        binding.btnPause.isEnabled = true
        binding.btnStop.isEnabled = true
    }

    private fun stopScan() {
        startService(Intent(this, DomainScanService::class.java).apply {
            action = DomainScanService.ACTION_STOP
        })
        binding.btnStart.isEnabled = true
        binding.btnPause.isEnabled = false
        binding.btnStop.isEnabled = false
        binding.btnPause.text = "⏸️ إيقاف مؤقت"
    }

    private fun startUiUpdate() {
        uiUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                if (DomainScanService.isRunning) {
                    val p = DomainScanService.progress
                    val t = DomainScanService.total.takeIf { it > 0 } ?: totalDomainsInFile
                    val percent = if (t > 0) (p * 100 / t) else 0
                    binding.tvProgress.text = "$p / $t"
                    binding.progressBar.max = t
                    binding.progressBar.progress = p
                    binding.tvRegistered.text = "✅ ${DomainScanService.registered}"
                    binding.tvFailed.text = "❌ ${DomainScanService.failed}"
                    binding.tvIgnored.text = "⏭️ ${DomainScanService.ignored}"
                    binding.tvEta.text = DomainScanService.estimatedTimeLeft

                    // تحديث الـ session في ViewModel
                    if (DomainScanService.currentSessionId != -1L) {
                        viewModel.setSession(DomainScanService.currentSessionId)
                    }

                    if (!DomainScanService.isRunning) {
                        binding.btnStart.isEnabled = true
                        binding.btnPause.isEnabled = false
                        binding.btnStop.isEnabled = false
                    }
                }
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiUpdateJob?.cancel()
    }
}
