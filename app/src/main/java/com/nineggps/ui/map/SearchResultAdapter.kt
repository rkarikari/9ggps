// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.map

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nineggps.data.model.SearchResult
import com.nineggps.databinding.ItemSearchResultBinding

class SearchResultAdapter(
    results: List<SearchResult> = emptyList(),
    private val onItemClick: (SearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    private val results: MutableList<SearchResult> = results.toMutableList()

    fun updateResults(newResults: List<SearchResult>) {
        results.clear()
        results.addAll(newResults)
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(result: SearchResult) {
            binding.tvName.text = result.displayName.split(",").firstOrNull()?.trim() ?: result.displayName
            binding.tvAddress.text = result.displayName
            binding.tvType.text = result.type.replace("_", " ").replaceFirstChar { it.uppercase() }
            binding.root.setOnClickListener { onItemClick(result) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(results[position])
    }

    override fun getItemCount() = results.size
}
