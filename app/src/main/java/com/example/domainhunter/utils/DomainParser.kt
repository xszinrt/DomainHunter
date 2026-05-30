package com.example.domainhunter.utils

object DomainParser {

    fun extractDomain(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        // تجاهل سطر الـ header
        if (trimmed.lowercase().startsWith("domain,") ||
            trimmed.lowercase() == "domain") return null

        // تنسيق CSV — النطاق في العمود الأول
        val firstCol = trimmed.split(",")[0].trim()
            .removePrefix("\"").removeSuffix("\"").trim().lowercase()

        // تنسيق TXT — السطر كله هو النطاق
        val fullLine = trimmed.lowercase()

        return when {
            firstCol.endsWith(".com") -> firstCol
            fullLine.endsWith(".com") -> fullLine
            else -> null
        }
    }
}
