// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.map

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nineggps.R
import com.nineggps.data.db.entity.WaypointEntity
import com.nineggps.databinding.ItemWaypointOrderBinding

/**
 * Adapter for the "Route via waypoints" ordered-selection dialog.
 *
 * Tapping a row selects it and appends it to [orderedSelection], showing its
 * 1-based stop number in a filled-circle badge.  Tapping a selected row
 * removes it from [orderedSelection] and renumbers any remaining stops.
 *
 * [onSelectionChanged] is fired after every change so the host dialog can
 * update its summary strip and enable/disable the Navigate button.
 */
class WaypointOrderAdapter(
    private val onSelectionChanged: (ordered: List<WaypointEntity>) -> Unit
) : ListAdapter<WaypointEntity, WaypointOrderAdapter.VH>(Diff()) {

    /** Waypoints in the order the user tapped them. */
    private val orderedSelection = mutableListOf<WaypointEntity>()

    /** Returns the current ordered selection (a snapshot copy). */
    fun getOrderedSelection(): List<WaypointEntity> = orderedSelection.toList()

    inner class VH(private val b: ItemWaypointOrderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(wp: WaypointEntity) {
            b.tvWpName.text = wp.name
            b.tvWpCoords.text = String.format("%.5f, %.5f", wp.latitude, wp.longitude)

            val stopIndex = orderedSelection.indexOf(wp)   // -1 if not selected
            val isSelected = stopIndex >= 0

            // Badge appearance
            if (isSelected) {
                b.tvBadge.text   = (stopIndex + 1).toString()
                b.tvBadge.background =
                    ContextCompat.getDrawable(b.root.context, R.drawable.bg_badge_selected)
                b.tvBadge.setTextColor(
                    ContextCompat.getColor(b.root.context, android.R.color.white)
                )
            } else {
                b.tvBadge.text   = ""
                b.tvBadge.background =
                    ContextCompat.getDrawable(b.root.context, R.drawable.bg_badge_unselected)
            }

            // Card highlight
            val strokeColor = if (isSelected)
                ContextCompat.getColor(b.root.context, R.color.primary)
            else
                android.graphics.Color.TRANSPARENT
            b.cardRoot.strokeColor = strokeColor

            // Toggle selection on tap
            b.root.setOnClickListener {
                if (isSelected) {
                    orderedSelection.remove(wp)
                } else {
                    orderedSelection.add(wp)
                }
                // Rebind ALL visible items so badge numbers stay consistent
                notifyDataSetChanged()
                onSelectionChanged(orderedSelection.toList())
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemWaypointOrderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    private class Diff : DiffUtil.ItemCallback<WaypointEntity>() {
        override fun areItemsTheSame(a: WaypointEntity, b: WaypointEntity) = a.id == b.id
        override fun areContentsTheSame(a: WaypointEntity, b: WaypointEntity) = a == b
    }
}
