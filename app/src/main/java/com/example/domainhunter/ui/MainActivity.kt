package com.example.domainhunter.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.domainhunter.databinding.ActivityMainBinding
import com.example.domainhunter.service.DomainScanService
import com.example.domainhunter.utils.ExportHelper
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val adapter = DomainAdapter()
    private var filePath: String? = null
    private var currentSessionId: Long = -1L

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val file = File(cacheDir, "domains.txt")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            filePath = file.absolutePath
            binding.tvFileName.text = "✅ ${file.name}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        viewModel.domains.observe(this) { adapter.submitList(it) }

        // استيراد ملف
        binding.btnImport.setOnClickListener {
            filePicker.launch("text/*")
        }

        // بدأ
        binding.btnStart.setOnClickListener {
            if (filePath == null) {
                Toast.makeText(this, "اختر ملفاً أولاً!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (DomainScanService.isPaused) {
                DomainScanService.isPaused = false
                binding.btnStart.text = "▶️ بدأ"
                return@setOnClickListener
            }
            startScan()
        }

        // إيقاف مؤقت
        binding.btnPause.setOnClickListener {
            DomainScanService.isPaused = !DomainScanService.isPaused
            binding.btnPause.text = if (DomainScanService.isPaused) "▶️ استئناف" else "⏸️ إيقاف مؤقت"
        }

        // إنهاء
        binding.btnStop.setOnClickListener {
            stopScan()
        }

        // بحث
        binding.etSearch.addTextChangedListener {
            viewModel.setSearch(it.toString())
        }

        // تصدير
        binding.btnExport.setOnClickListener {
            val domains = adapter.currentList
            if (domains.isEmpty()) {
                Toast.makeText(this, "لا توجد نتائج!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent.createChooser(
                ExportHelper.exportToCsv(this, domains), "تصدير النتائج"
            ))
        }

        // نسخ الكل
        binding.btnCopyAll.setOnClickListener {
            val domains = adapter.currentList
            if (domains.isEmpty()) {
                Toast.makeText(this, "لا توجد نتائج!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("domains", ExportHelper.copyAll(domains)))
            Toast.makeText(this, "تم نسخ ${domains.size} نطاق!", Toast.LENGTH_SHORT).show()
        }

        // تحديث التقدم
        updateProgressLoop()
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
        binding.btnStart.isEnabled = false
        binding.btnPause.isEnabled = true
        binding.btnStop.isEnabled = true
    }

    private fun stopScan() {
        val intent = Intent(this, DomainScanService::class.java).apply {
            action = DomainScanService.ACTION_STOP
        }
        startService(intent)
        binding.btnStart.isEnabled = true
        binding.btnPause.isEnabled = false
        binding.btnStop.isEnabled = false
        binding.btnPause.text = "⏸️ إيقاف مؤقت"
    }

    private fun updateProgressLoop() {
        binding.root.postDelayed({
            if (DomainScanService.isRunning) {
                val p = DomainScanService.progress
                val t = DomainScanService.total
                val percent = if (t > 0) (p * 100 / t) else 0
                binding.tvProgress.text = "$p / $t"
                binding.progressBar.progress = percent
                binding.tvRegistered.text = "✅ ${DomainScanService.registered}"
                binding.tvFailed.text = "❌ ${DomainScanService.failed}"
                binding.tvIgnored.text = "⏭️ ${DomainScanService.ignored}"
                binding.tvEta.text = DomainScanService.estimatedTimeLeft
            }
            updateProgressLoop()
        }, 1000)
    }
}
