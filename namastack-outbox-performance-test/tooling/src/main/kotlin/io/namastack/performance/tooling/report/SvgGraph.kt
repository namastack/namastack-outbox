package io.namastack.performance.tooling.report

import io.namastack.performance.tooling.internal.format
import io.namastack.performance.tooling.internal.xml
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.math.max

internal object SvgGraph {
    private val colors = listOf("#2563eb", "#dc2626", "#16a34a", "#9333ea", "#ea580c", "#0891b2")

    fun write(
        file: Path,
        title: String,
        series: List<MetricSeries>,
    ) {
        val nonEmpty = series.filter { it.points.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            file.writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="180"><text x="30" y="42" font-family="sans-serif" font-size="18">${xml(title)}</text><text x="30" y="92" font-family="sans-serif" font-size="14">No samples available</text></svg>""" + "\n")
            return
        }
        val points = nonEmpty.flatMap { it.points }
        val minTime = points.minOf { it.timestamp.toEpochMilli() }
        val maxTime = max(minTime + 1, points.maxOf { it.timestamp.toEpochMilli() })
        val maxValue = max(1.0, points.maxOf { it.value })
        val left = 75.0
        val top = 45.0
        val width = 890.0
        val height = 260.0
        fun x(time: Instant) = left + (time.toEpochMilli() - minTime).toDouble() / (maxTime - minTime) * width
        fun y(value: Double) = top + height - value / maxValue * height
        file.writeText(
            buildString {
                appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="360">""")
                appendLine("""<rect width="100%" height="100%" fill="white"/>""")
                appendLine("""<text x="$left" y="24" font-family="sans-serif" font-size="18" font-weight="bold">${xml(title)}</text>""")
                appendLine("""<line x1="$left" y1="$top" x2="$left" y2="${top + height}" stroke="#64748b"/>""")
                appendLine("""<line x1="$left" y1="${top + height}" x2="${left + width}" y2="${top + height}" stroke="#64748b"/>""")
                appendLine("""<text x="8" y="50" font-family="sans-serif" font-size="12">${format(maxValue)}</text>""")
                nonEmpty.forEachIndexed { index, current ->
                    val color = colors[index % colors.size]
                    appendLine("""<polyline points="${current.points.joinToString(" ") { "${format(x(it.timestamp))},${format(y(it.value))}" }}" fill="none" stroke="$color" stroke-width="2"/>""")
                    appendLine("""<text x="${left + index * 180}" y="338" font-family="sans-serif" font-size="12" fill="$color">${xml(current.name)}</text>""")
                }
                appendLine("</svg>")
            },
        )
    }
}
