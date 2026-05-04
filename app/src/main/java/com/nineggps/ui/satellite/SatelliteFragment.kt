// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.satellite

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.AttributeSet
import android.view.*
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.*
import com.nineggps.R
import com.nineggps.data.model.SatelliteInfo
import com.nineggps.data.model.SatelliteState
import com.nineggps.databinding.FragmentSatelliteBinding
import com.nineggps.databinding.ItemSatelliteBinding
import com.nineggps.service.GpsTrackingService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class SatelliteViewModel @Inject constructor() : ViewModel() {
    val satelliteState: StateFlow<SatelliteState> = GpsTrackingService.satelliteState
    val gpsState = GpsTrackingService.gpsState
}

// ─── Fragment ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class SatelliteFragment : Fragment(R.layout.fragment_satellite) {

    private var _binding: FragmentSatelliteBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SatelliteViewModel by viewModels()
    private val adapter = SatelliteAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSatelliteBinding.bind(view)

        binding.rvSatellites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSatellites.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.satelliteState.collectLatest { state ->
                        updateSummary(state)
                        binding.skyView.updateSatellites(state.satellites)
                        binding.signalBarsView.updateSatellites(state.satellites)
                        adapter.submitList(state.satellites)
                        binding.tvNoSignal.isVisible = state.totalCount == 0
                        binding.rvSatellites.isVisible = state.totalCount > 0
                    }
                }

                launch {
                    viewModel.gpsState.collectLatest { gps ->
                        binding.tvLatLon.text = gps.location?.let {
                            "%.6f°, %.6f°".format(it.latitude, it.longitude)
                        } ?: "No fix"
                        binding.tvAltitude.text = "%.1f m".format(gps.altitude)
                        binding.tvAccuracy.text = "±%.1f m".format(gps.accuracy)
                        binding.tvSpeed.text = "%.1f km/h".format(gps.speed * 3.6f)
                        binding.tvBearing.text = "%.1f°".format(gps.bearing)
                        binding.tvProvider.text = gps.provider.ifBlank { "—" }
                        val fixColor = if (gps.isFixed)
                            ContextCompat.getColor(requireContext(), R.color.gps_fix_good)
                        else
                            ContextCompat.getColor(requireContext(), R.color.gps_fix_bad)
                        binding.ivFixIndicator.setColorFilter(fixColor)
                        binding.tvFixStatus.text = if (gps.isFixed) "FIX" else "NO FIX"
                        binding.tvFixStatus.setTextColor(fixColor)
                    }
                }
            }
        }
    }

    private fun updateSummary(state: SatelliteState) {
        binding.tvSatTotal.text = state.totalCount.toString()
        binding.tvSatUsed.text = state.usedInFixCount.toString()
        binding.tvAvgCn0.text = "%.1f dB-Hz".format(state.avgCn0)
        binding.tvMaxCn0.text = "%.1f dB-Hz".format(state.maxCn0)

        // Constellation breakdown
        val counts = state.satellites.groupBy { it.constellation }
            .map { (name, list) ->
                val used = list.count { it.usedInFix }
                "$name: ${used}/${list.size}"
            }.joinToString("  •  ")
        binding.tvConstellations.text = counts.ifBlank { "—" }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─── RecyclerView Adapter ─────────────────────────────────────────────────────

class SatelliteAdapter : ListAdapter<SatelliteInfo, SatelliteAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SatelliteInfo>() {
            override fun areItemsTheSame(a: SatelliteInfo, b: SatelliteInfo) =
                a.svid == b.svid && a.constellationCode == b.constellationCode
            override fun areContentsTheSame(a: SatelliteInfo, b: SatelliteInfo) = a == b
        }
    }

    inner class VH(val binding: ItemSatelliteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemSatelliteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sat = getItem(position)
        with(holder.binding) {
            tvPrn.text = "PRN %02d".format(sat.svid)
            tvConstellation.text = sat.constellation
            tvCn0.text = "%.1f".format(sat.cn0DbHz)
            tvElevation.text = "El: %.0f°".format(sat.elevationDegrees)
            tvAzimuth.text = "Az: %.0f°".format(sat.azimuthDegrees)

            // Frequency label (L1 = 1575.42 MHz, L2 = 1227.60, L5 = 1176.45, etc.)
            tvFrequency.text = if (sat.carrierFrequencyHz > 0f)
                "%.2f MHz".format(sat.carrierFrequencyHz / 1_000_000f)
            else "—"

            // Signal bar fill (0–50 dB-Hz → 0–100%)
            val pct = (sat.cn0DbHz / 50f).coerceIn(0f, 1f)
            signalBar.progress = (pct * 100).toInt()
            signalBar.progressTintList = android.content.res.ColorStateList.valueOf(
                when {
                    sat.cn0DbHz >= 35f -> 0xFF4CAF50.toInt()  // green
                    sat.cn0DbHz >= 20f -> 0xFFFF9800.toInt()  // orange
                    else               -> 0xFFF44336.toInt()  // red
                }
            )

            // Status badges
            ivUsedInFix.isVisible = sat.usedInFix
            tvHasAlmanac.isVisible = sat.hasAlmanac
            tvHasEphemeris.isVisible = sat.hasEphemeris

            // Constellation color chip
            tvConstellation.setBackgroundColor(constellationColor(sat.constellationCode))
        }
    }

    private fun constellationColor(code: Int): Int = when (code) {
        1 -> 0xFF1565C0.toInt()  // GPS – blue
        2 -> 0xFF6A1B9A.toInt()  // SBAS – purple
        3 -> 0xFF2E7D32.toInt()  // GLONASS – green
        4 -> 0xFFBF360C.toInt()  // QZSS – deep orange
        5 -> 0xFF00695C.toInt()  // BeiDou – teal
        6 -> 0xFF558B2F.toInt()  // Galileo – light-green
        7 -> 0xFF4E342E.toInt()  // IRNSS/NavIC – brown
        else -> 0xFF546E7A.toInt()
    }
}

// ─── Sky Plot View ────────────────────────────────────────────────────────────

/**
 * Polar plot showing satellite positions on the sky dome.
 * Centre = zenith (elevation 90°), outer ring = horizon (elevation 0°).
 * Azimuth runs clockwise from North (top).
 */
class SkyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var satellites: List<SatelliteInfo> = emptyList()

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D1B2A.toInt()
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4DFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val elLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    private val satPaintUsed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val satPaintUnused = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 160
    }
    private val satStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFFFFFF.toInt()
    }
    private val satTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    private val constellationColors = mapOf(
        1 to 0xFF42A5F5.toInt(),  // GPS – blue
        3 to 0xFF66BB6A.toInt(),  // GLONASS – green
        5 to 0xFF26C6DA.toInt(),  // BeiDou – cyan
        6 to 0xFFFFCA28.toInt(),  // Galileo – yellow
        4 to 0xFFFFA726.toInt(),  // QZSS – orange
        7 to 0xFFEF5350.toInt(),  // NavIC – red
        2 to 0xFFAB47BC.toInt(),  // SBAS – purple
    )

    fun updateSatellites(sats: List<SatelliteInfo>) {
        satellites = sats
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - labelPaint.textSize - 8f

        // Background circle
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // Elevation rings: 0°, 30°, 60°, 90°(centre)
        for (el in intArrayOf(0, 30, 60)) {
            val r = radius * (1f - el / 90f)
            canvas.drawCircle(cx, cy, r, gridPaint)
            val labelY = cy - r + elLabelPaint.textSize
            canvas.drawText("${el}°", cx, labelY, elLabelPaint)
        }
        canvas.drawPoint(cx, cy, gridPaint.apply { strokeWidth = 4f })
        gridPaint.strokeWidth = 1.5f

        // Cardinal lines
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint)

        // Cardinal labels
        val pad = labelPaint.textSize + 2f
        canvas.drawText("N", cx, cy - radius - 4f, labelPaint)
        canvas.drawText("S", cx, cy + radius + pad, labelPaint)
        canvas.drawText("W", cx - radius - 4f, cy + labelPaint.textSize / 3f, labelPaint)
        canvas.drawText("E", cx + radius + 4f, cy + labelPaint.textSize / 3f, labelPaint)

        // Satellites
        for (sat in satellites) {
            val azRad = Math.toRadians(sat.azimuthDegrees.toDouble())
            val elFrac = 1f - sat.elevationDegrees / 90f  // 0 = centre, 1 = edge
            val sx = cx + (radius * elFrac * sin(azRad)).toFloat()
            val sy = cy - (radius * elFrac * cos(azRad)).toFloat()

            val dotR = lerp(8f, 18f, (sat.cn0DbHz / 50f).coerceIn(0f, 1f))
            val color = constellationColors[sat.constellationCode] ?: 0xFF90A4AE.toInt()

            val paint = if (sat.usedInFix) satPaintUsed else satPaintUnused
            paint.color = color
            canvas.drawCircle(sx, sy, dotR, paint)
            if (sat.usedInFix) canvas.drawCircle(sx, sy, dotR, satStrokePaint)

            // PRN label inside larger dots
            if (dotR >= 13f) {
                satTextPaint.textSize = (dotR * 0.85f).coerceAtLeast(16f)
                canvas.drawText(sat.svid.toString(), sx, sy + satTextPaint.textSize / 3f, satTextPaint)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

// ─── Signal Bars View ─────────────────────────────────────────────────────────

/**
 * Horizontal bar chart: one column per satellite, coloured by signal strength.
 * Satellites used in fix are outlined. Constellation type labels below.
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var satellites: List<SatelliteInfo> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = 0xFFFFFFFF.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint().apply {
        color = 0x1AFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val refLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }

    fun updateSatellites(sats: List<SatelliteInfo>) {
        satellites = sats
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (satellites.isEmpty()) return

        val bottomPad = labelPaint.textSize + 6f
        val topPad = 24f
        val chartH = height - bottomPad - topPad
        val maxDb = 50f

        val barW = (width.toFloat() / satellites.size).coerceAtMost(40f)
        val totalW = barW * satellites.size
        val startX = (width - totalW) / 2f

        // Reference lines at 20 and 35 dB-Hz
        for (ref in floatArrayOf(20f, 35f)) {
            val ry = topPad + chartH * (1f - ref / maxDb)
            canvas.drawLine(startX, ry, startX + totalW, ry, refLinePaint)
        }

        satellites.forEachIndexed { i, sat ->
            val left = startX + i * barW + 2f
            val right = left + barW - 4f
            val barH = chartH * (sat.cn0DbHz / maxDb).coerceIn(0f, 1f)
            val top = topPad + chartH - barH
            val bot = topPad + chartH

            // Background slot
            canvas.drawRect(left, topPad, right, bot, bgPaint)

            // Bar colour
            barPaint.color = when {
                sat.cn0DbHz >= 35f -> 0xFF4CAF50.toInt()
                sat.cn0DbHz >= 20f -> 0xFFFF9800.toInt()
                else               -> 0xFFF44336.toInt()
            }
            canvas.drawRect(left, top, right, bot, barPaint)

            // Outline if used in fix
            if (sat.usedInFix) canvas.drawRect(left, top, right, bot, outlinePaint)

            // PRN label below
            canvas.drawText(
                sat.svid.toString(),
                left + (right - left) / 2f,
                height - 2f,
                labelPaint
            )
        }
    }
}
