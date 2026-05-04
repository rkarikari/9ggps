// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.statistics

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.charts.*
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.nineggps.R
import com.nineggps.data.db.entity.TrackEntity
import com.nineggps.data.repository.TrackRepository
import com.nineggps.databinding.FragmentStatisticsBinding
import com.nineggps.utils.NineGUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    val tracks: StateFlow<List<TrackEntity>> = repository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weeklyDistance: StateFlow<Map<String, Double>> = tracks.map { list ->
        val sdf = SimpleDateFormat("EEE", Locale.getDefault())
        val cal = Calendar.getInstance()
        val result = linkedMapOf<String, Double>()
        for (i in 6 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis() - i * 86400000L
            result[sdf.format(cal.time)] = 0.0
        }
        list.forEach { track ->
            cal.timeInMillis = track.startTime
            val day = sdf.format(cal.time)
            if (result.containsKey(day)) {
                result[day] = (result[day] ?: 0.0) + track.distance / 1000.0
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val activityDistribution: StateFlow<Map<String, Int>> = tracks.map { list ->
        list.groupBy { it.activityType }.mapValues { it.value.size }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val monthlyStats: StateFlow<List<Triple<String, Double, Long>>> = tracks.map { list ->
        val sdf = SimpleDateFormat("MMM", Locale.getDefault())
        val monthMap = linkedMapOf<String, Pair<Double, Long>>()
        list.forEach { track ->
            val month = sdf.format(Date(track.startTime))
            val (dist, dur) = monthMap[month] ?: Pair(0.0, 0L)
            monthMap[month] = Pair(dist + track.distance, dur + track.duration)
        }
        monthMap.entries.toList().takeLast(6).map { (month, data) ->
            Triple(month, data.first, data.second)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@AndroidEntryPoint
class StatisticsFragment : Fragment(R.layout.fragment_statistics) {

    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatisticsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentStatisticsBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.tracks.collectLatest { tracks ->
                        updateSummary(tracks)
                        updateSpeedHistogram(tracks)
                    }
                }

                launch {
                    viewModel.weeklyDistance.collectLatest { data ->
                        updateWeeklyChart(data)
                    }
                }

                launch {
                    viewModel.activityDistribution.collectLatest { data ->
                        updateActivityPieChart(data)
                    }
                }

                launch {
                    viewModel.monthlyStats.collectLatest { data ->
                        updateMonthlyChart(data)
                    }
                }
            }
        }
    }

    private fun updateSummary(tracks: List<TrackEntity>) {
        val totalDist  = tracks.sumOf { it.distance }
        val totalDur   = tracks.sumOf { it.duration }
        val totalCal   = tracks.sumOf { it.calories }
        val maxSpd     = tracks.maxOfOrNull { it.maxSpeed } ?: 0f
        val totalElev  = tracks.sumOf { it.elevationGain }
        val avgSpd     = if (tracks.isNotEmpty()) tracks.map { it.avgSpeed }.average() else 0.0

        with(binding) {
            tvTotalTracks.text    = "${tracks.size}"
            tvTotalDistance.text  = NineGUtils.formatDistance(totalDist)
            tvTotalTime.text      = NineGUtils.formatDuration(totalDur)
            tvTotalCalories.text  = "$totalCal kcal"
            tvMaxSpeed.text       = "${maxSpd.toInt()} km/h"
            tvTotalElevation.text = "+${NineGUtils.formatAltitude(totalElev)}"
            tvAvgSpeed.text       = "${avgSpd.toInt()} km/h avg"
            tvLongestTrack.text   = NineGUtils.formatDistance(tracks.maxOfOrNull { it.distance } ?: 0.0)
        }
    }

    private fun updateWeeklyChart(data: Map<String, Double>) {
        val chart = binding.chartWeeklyDistance
        setupBarChart(chart)
        val entries = data.values.mapIndexed { i, v -> BarEntry(i.toFloat(), v.toFloat()) }
        val labels  = data.keys.toList()
        val dataset = BarDataSet(entries, "km").apply {
            colors = entries.map {
                val alpha = (0.4f + 0.6f * (it.y / (data.values.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f)))
                Color.argb((alpha * 255).toInt(), 33, 150, 243)
            }
            setDrawValues(true)
            valueTextSize = 9f
        }
        chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        chart.data = BarData(dataset)
        chart.animateY(600)
    }

    private fun updateActivityPieChart(data: Map<String, Int>) {
        val chart = binding.chartActivities
        val entries = data.map { (type, count) -> PieEntry(count.toFloat(), type) }
        if (entries.isEmpty()) return
        val colours = listOf(
            Color.parseColor("#2196F3"),
            Color.parseColor("#4CAF50"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#F44336")
        )
        val dataset = PieDataSet(entries, "").apply {
            colors = colours.take(entries.size)
            valueTextSize = 11f
            valueTextColor = Color.WHITE
            sliceSpace = 3f
        }
        chart.apply {
            this.data = PieData(dataset)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 50f
            transparentCircleRadius = 55f
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(10f)
            animateY(800)
        }
    }

    private fun updateMonthlyChart(data: List<Triple<String, Double, Long>>) {
        val chart = binding.chartMonthlyDistance
        setupBarChart(chart)
        val entries = data.mapIndexed { i, (_, dist, _) -> BarEntry(i.toFloat(), (dist / 1000).toFloat()) }
        val labels  = data.map { it.first }
        val dataset = BarDataSet(entries, "km/month").apply {
            color = Color.parseColor("#1565C0")
            setDrawValues(true)
            valueTextSize = 9f
        }
        chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        chart.data = BarData(dataset)
        chart.animateY(600)
    }

    private fun updateSpeedHistogram(tracks: List<TrackEntity>) {
        val chart = binding.chartSpeedDist
        setupBarChart(chart)
        val buckets = IntArray(10) // 0-20, 20-40 ... 180-200
        tracks.forEach { track ->
            val bucket = (track.avgSpeed / 20).toInt().coerceIn(0, 9)
            buckets[bucket]++
        }
        val entries = buckets.mapIndexed { i, count -> BarEntry(i.toFloat(), count.toFloat()) }
        val labels  = (0 until 10).map { "${it * 20}-${(it + 1) * 20}" }
        val dataset = BarDataSet(entries, "tracks").apply {
            color = Color.parseColor("#4CAF50")
            setDrawValues(false)
        }
        chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        chart.xAxis.labelRotationAngle = -45f
        chart.data = BarData(dataset)
        chart.animateY(600)
    }

    private fun setupBarChart(chart: BarChart) {
        chart.apply {
            description.isEnabled = false
            setDrawGridBackground(false)
            setPinchZoom(false)
            legend.isEnabled = false
            axisRight.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.argb(20, 0, 0, 0)
                axisMinimum = 0f
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
