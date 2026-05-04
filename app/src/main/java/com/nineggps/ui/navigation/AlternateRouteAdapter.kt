// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.navigation

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.nineggps.R
import com.nineggps.data.model.AlternateRoute
import com.nineggps.data.model.TrafficCondition
import com.nineggps.utils.NineGUtils

/**
 * Displays a horizontal list of [AlternateRoute] cards inside the route-preview
 * panel.  Tapping a card calls [onSelect] with the route's [AlternateRoute.id].
 */
class AlternateRouteAdapter(
    private val onSelect: (Int) -> Unit
) : ListAdapter<AlternateRoute, AlternateRouteAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView.findViewById(R.id.cardAlternate)
        val tvLabel: TextView      = itemView.findViewById(R.id.tvAltLabel)
        val tvTime: TextView       = itemView.findViewById(R.id.tvAltTime)
        val tvDist: TextView       = itemView.findViewById(R.id.tvAltDist)
        val tvTraffic: TextView    = itemView.findViewById(R.id.tvAltTraffic)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alternate_route, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val route = getItem(position)
        val ctx   = holder.itemView.context

        holder.tvLabel.text   = route.label
        holder.tvTime.text    = NineGUtils.formatEta(route.effectiveDuration)
        holder.tvDist.text    = NineGUtils.formatDistance(route.totalDistance)
        holder.tvTraffic.text = trafficEmoji(route.trafficCondition) + " " + route.trafficCondition.label

        // Highlight the active route
        if (route.isActive) {
            holder.card.strokeWidth = 4
            holder.card.strokeColor = ContextCompat.getColor(ctx, R.color.accent_blue)
            holder.tvLabel.setTypeface(null, Typeface.BOLD)
        } else {
            holder.card.strokeWidth = 1
            holder.card.strokeColor = ContextCompat.getColor(ctx, R.color.accent_blue).and(0x40FFFFFF.toInt())
            holder.tvLabel.setTypeface(null, Typeface.NORMAL)
        }

        holder.card.setOnClickListener { onSelect(route.id) }
    }

    private fun trafficEmoji(condition: TrafficCondition): String = when (condition) {
        TrafficCondition.FREE_FLOW  -> "🟢"
        TrafficCondition.MODERATE   -> "🟡"
        TrafficCondition.HEAVY      -> "🟠"
        TrafficCondition.STANDSTILL -> "🔴"
        TrafficCondition.UNKNOWN    -> "⚪"
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AlternateRoute>() {
            override fun areItemsTheSame(a: AlternateRoute, b: AlternateRoute) = a.id == b.id
            override fun areContentsTheSame(a: AlternateRoute, b: AlternateRoute) = a == b
        }
    }
}
