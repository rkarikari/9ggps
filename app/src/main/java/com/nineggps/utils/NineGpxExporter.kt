// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.utils

import android.content.Context
import android.net.Uri
import com.nineggps.data.db.entity.TrackEntity
import com.nineggps.data.db.entity.TrackPointEntity
import com.nineggps.data.db.entity.WaypointEntity
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

object NineGpxExporter {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ─── Export Track to GPX ──────────────────────────────────────────────────

    fun exportTrack(
        track: TrackEntity,
        points: List<TrackPointEntity>,
        waypoints: List<WaypointEntity> = emptyList()
    ): String {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc: Document = builder.newDocument()

        // GPX root
        val gpx = doc.createElement("gpx").apply {
            setAttribute("version", "1.1")
            setAttribute("creator", "NineGGPS for Android")
            setAttribute("xmlns", "http://www.topografix.com/GPX/1/1")
            setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            setAttribute("xsi:schemaLocation",
                "http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd")
        }
        doc.appendChild(gpx)

        // Metadata
        val metadata = doc.createElement("metadata")
        metadata.appendChild(doc.createTextNode("name").apply {}).also {
            val nameEl = doc.createElement("name")
            nameEl.textContent = track.name
            metadata.appendChild(nameEl)
        }
        val descEl = doc.createElement("desc")
        descEl.textContent = "Exported from NineGGPS. Distance: ${
            NineGUtils.formatDistance(track.distance)}, Duration: ${NineGUtils.formatDuration(track.duration)}"
        metadata.appendChild(descEl)
        val timeEl = doc.createElement("time")
        timeEl.textContent = isoFormat.format(Date(track.startTime))
        metadata.appendChild(timeEl)
        gpx.appendChild(metadata)

        // Waypoints
        waypoints.forEach { wpt ->
            val wptEl = doc.createElement("wpt").apply {
                setAttribute("lat", wpt.latitude.toString())
                setAttribute("lon", wpt.longitude.toString())
            }
            wptEl.appendChild(doc.createElement("name").apply { textContent = wpt.name })
            wptEl.appendChild(doc.createElement("ele").apply { textContent = wpt.altitude.toString() })
            wptEl.appendChild(doc.createElement("desc").apply { textContent = wpt.description })
            gpx.appendChild(wptEl)
        }

        // Track
        val trkEl = doc.createElement("trk")
        trkEl.appendChild(doc.createElement("name").apply { textContent = track.name })
        trkEl.appendChild(doc.createElement("type").apply { textContent = track.activityType })

        val trkseg = doc.createElement("trkseg")
        points.forEach { point ->
            val trkpt = doc.createElement("trkpt").apply {
                setAttribute("lat", point.latitude.toString())
                setAttribute("lon", point.longitude.toString())
            }
            trkpt.appendChild(doc.createElement("ele").apply { textContent = point.altitude.toString() })
            trkpt.appendChild(doc.createElement("time").apply {
                textContent = isoFormat.format(Date(point.timestamp))
            })
            if (point.speed > 0) {
                val extensions = doc.createElement("extensions")
                extensions.appendChild(doc.createElement("speed").apply {
                    textContent = point.speed.toString()
                })
                extensions.appendChild(doc.createElement("course").apply {
                    textContent = point.bearing.toString()
                })
                extensions.appendChild(doc.createElement("accuracy").apply {
                    textContent = point.accuracy.toString()
                })
                trkpt.appendChild(extensions)
            }
            trkseg.appendChild(trkpt)
        }
        trkEl.appendChild(trkseg)
        gpx.appendChild(trkEl)

        // Transform to string
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    // ─── Export to KML ────────────────────────────────────────────────────────

    fun exportTrackKml(track: TrackEntity, points: List<TrackPointEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        sb.appendLine("""  <Document>""")
        sb.appendLine("""    <name>${track.name}</name>""")
        sb.appendLine("""    <description>Distance: ${NineGUtils.formatDistance(track.distance)}, Duration: ${NineGUtils.formatDuration(track.duration)}</description>""")
        sb.appendLine("""    <Style id="trackStyle">""")
        sb.appendLine("""      <LineStyle><color>ff${track.color.replace("#","").takeLast(6)}</color><width>4</width></LineStyle>""")
        sb.appendLine("""    </Style>""")
        sb.appendLine("""    <Placemark>""")
        sb.appendLine("""      <name>${track.name}</name>""")
        sb.appendLine("""      <styleUrl>#trackStyle</styleUrl>""")
        sb.appendLine("""      <LineString>""")
        sb.appendLine("""        <altitudeMode>clampToGround</altitudeMode>""")
        sb.appendLine("""        <coordinates>""")
        points.forEach { p ->
            sb.appendLine("          ${p.longitude},${p.latitude},${p.altitude}")
        }
        sb.appendLine("""        </coordinates>""")
        sb.appendLine("""      </LineString>""")
        sb.appendLine("""    </Placemark>""")
        sb.appendLine("""  </Document>""")
        sb.appendLine("""</kml>""")
        return sb.toString()
    }

    // ─── Save to File ─────────────────────────────────────────────────────────

    fun saveToFile(context: Context, content: String, filename: String): File {
        val dir = File(context.getExternalFilesDir(null), "NineGGPS/exports")
        dir.mkdirs()
        val file = File(dir, filename)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    // ─── Import GPX ───────────────────────────────────────────────────────────

    data class ImportResult(
        val trackName: String,
        val points: List<ImportPoint>,
        val waypoints: List<ImportWaypoint>
    )

    data class ImportPoint(
        val lat: Double,
        val lon: Double,
        val alt: Double,
        val timestamp: Long,
        val speed: Float = 0f
    )

    data class ImportWaypoint(
        val name: String,
        val lat: Double,
        val lon: Double,
        val alt: Double,
        val description: String
    )

    fun importGpx(context: Context, uri: Uri): ImportResult? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseGpx(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ─── Import KML ───────────────────────────────────────────────────────────

    fun importKml(context: Context, uri: Uri): ImportResult? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseKml(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseKml(stream: InputStream): ImportResult {
        var trackName = "Imported Track"
        val points = mutableListOf<ImportPoint>()
        val waypoints = mutableListOf<ImportWaypoint>()

        var inCoordinates = false
        var inPlacemark = false
        var inLineString = false
        var inPoint = false
        var placeName = ""
        var placeDesc = ""
        var currentText = StringBuilder()

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
                currentText = StringBuilder()
                when (qName) {
                    "Placemark" -> { inPlacemark = true; placeName = ""; placeDesc = "" }
                    "LineString" -> inLineString = true
                    "Point" -> inPoint = true
                    "coordinates" -> inCoordinates = true
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                val text = currentText.toString().trim()
                when (qName) {
                    "name" -> if (inPlacemark) placeName = text else trackName = text
                    "description" -> if (inPlacemark) placeDesc = text
                    "coordinates" -> {
                        if (inLineString) {
                            // Each coordinate: lon,lat,alt separated by whitespace
                            text.trim().split(Regex("\\s+")).forEach { coord ->
                                val parts = coord.split(",")
                                if (parts.size >= 2) {
                                    val lon = parts[0].toDoubleOrNull() ?: return@forEach
                                    val lat = parts[1].toDoubleOrNull() ?: return@forEach
                                    val alt = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                                    points.add(ImportPoint(lat, lon, alt, 0L))
                                }
                            }
                        } else if (inPoint) {
                            val parts = text.split(",")
                            if (parts.size >= 2) {
                                val lon = parts[0].toDoubleOrNull() ?: 0.0
                                val lat = parts[1].toDoubleOrNull() ?: 0.0
                                val alt = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                                waypoints.add(ImportWaypoint(placeName, lat, lon, alt, placeDesc))
                            }
                        }
                        inCoordinates = false
                    }
                    "LineString" -> inLineString = false
                    "Point" -> inPoint = false
                    "Placemark" -> inPlacemark = false
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                currentText.append(ch, start, length)
            }
        }

        val saxFactory = SAXParserFactory.newInstance()
        val parser = saxFactory.newSAXParser()
        parser.parse(stream, handler)

        return ImportResult(trackName, points, waypoints)
    }

    private val parseIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseGpx(stream: InputStream): ImportResult {
        var trackName = "Imported Track"
        val points = mutableListOf<ImportPoint>()
        val waypoints = mutableListOf<ImportWaypoint>()

        var inTrkseg = false
        var inWpt = false
        var currentLat = 0.0
        var currentLon = 0.0
        var currentAlt = 0.0
        var currentTime = 0L
        var currentText = StringBuilder()
        var wptName = ""
        var wptDesc = ""

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
                currentText = StringBuilder()
                when (qName) {
                    "trkseg" -> inTrkseg = true
                    "trkpt" -> {
                        currentLat = attrs.getValue("lat")?.toDoubleOrNull() ?: 0.0
                        currentLon = attrs.getValue("lon")?.toDoubleOrNull() ?: 0.0
                        currentAlt = 0.0; currentTime = 0L
                    }
                    "wpt" -> {
                        inWpt = true
                        currentLat = attrs.getValue("lat")?.toDoubleOrNull() ?: 0.0
                        currentLon = attrs.getValue("lon")?.toDoubleOrNull() ?: 0.0
                        currentAlt = 0.0; wptName = ""; wptDesc = ""
                    }
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                val text = currentText.toString().trim()
                when (qName) {
                    "name" -> if (inWpt) wptName = text else if (!inTrkseg) trackName = text
                    "ele" -> currentAlt = text.toDoubleOrNull() ?: 0.0
                    "time" -> {
                        currentTime = try { parseIso.parse(text)?.time ?: 0L } catch (e: Exception) { 0L }
                    }
                    "desc" -> if (inWpt) wptDesc = text
                    "trkpt" -> {
                        if (currentLat != 0.0 || currentLon != 0.0) {
                            points.add(ImportPoint(currentLat, currentLon, currentAlt, currentTime))
                        }
                    }
                    "wpt" -> {
                        waypoints.add(ImportWaypoint(wptName, currentLat, currentLon, currentAlt, wptDesc))
                        inWpt = false
                    }
                    "trkseg" -> inTrkseg = false
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                currentText.append(ch, start, length)
            }
        }

        val saxFactory = SAXParserFactory.newInstance()
        val parser = saxFactory.newSAXParser()
        parser.parse(stream, handler)

        return ImportResult(trackName, points, waypoints)
    }
}
