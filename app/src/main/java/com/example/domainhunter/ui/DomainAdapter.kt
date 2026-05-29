package com.example.domainhunter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.domainhunter.data.model.Domain
import com.example.domainhunter.data.model.DomainStatus
import com.example.domainhunter.databinding.ItemDomainBinding

class DomainAdapter : ListAdapter<Domain, DomainAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemDomainBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(domain: Domain) {
            binding.tvDomainName.text = domain.domainName
            binding.tvRegDate.text = "📅 سُجّل: ${domain.registrationDate ?: "غير معروف"}"
            binding.tvExpDate.text = "⏳ ينتهي: ${domain.expirationDate ?: "غير معروف"}"
            binding.tvExpiringSoon.isVisible = domain.isExpiringSoon
            binding.tvFailed.isVisible = domain.status == DomainStatus.FAILED

            binding.btnOpen.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://${domain.domainName}"))
                it.context.startActivity(intent)
            }

            binding.btnCopy.setOnClickListener {
                val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("domain", domain.domainName))
                Toast.makeText(it.context, "تم النسخ!", Toast.LENGTH_SHORT).show()
            }

            binding.btnGoogle.setOnClickListener {
                val url = "https://www.google.com/search?q=site:${domain.domainName}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                it.context.startActivity(intent)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<Domain>() {
        override fun areItemsTheSame(oldItem: Domain, newItem: Domain) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Domain, newItem: Domain) = oldItem == newItem
    }
}
