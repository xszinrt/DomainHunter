package com.example.domainhunter.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.domainhunter.data.model.Domain
import java.io.File

object ExportHelper {

    fun exportToCsv(context: Context, domains: List<Domain>): Intent {
        val file = File(context.cacheDir, "results.csv")
        file.bufferedWriter().use { writer ->
            writer.write("النطاق,تاريخ التسجيل,تاريخ الانتهاء,الحالة\n")
            domains.forEach {
                writer.write("${it.domainName},${it.registrationDate ?: ""},${it.expirationDate ?: ""},${it.status}\n")
            }
        }
        return shareFile(context, file, "text/csv")
    }

    fun exportToTxt(context: Context, domains: List<Domain>): Intent {
        val file = File(context.cacheDir, "results.txt")
        file.bufferedWriter().use { writer ->
            domains.forEach {
                writer.write("${it.domainName} | سُجّل: ${it.registrationDate ?: "غير معروف"} | ينتهي: ${it.expirationDate ?: "غير معروف"}\n")
            }
        }
        return shareFile(context, file, "text/plain")
    }

    fun copyAll(domains: List<Domain>): String {
        return domains.joinToString("\n") { it.domainName }
    }

    private fun shareFile(context: Context, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
