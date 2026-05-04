// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.utils

import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * Centralised tile-source registry.
 *
 * Every source is defined as an explicit [XYTileSource] so the URL format,
 * zoom range, tile size and file extension are all under our control and not
 * subject to changes in the bundled [org.osmdroid.tileprovider.tilesource.TileSourceFactory].
 *
 * ─── Why three sources were broken ───────────────────────────────────────────
 * • OSM Standard: [TileSourceFactory.MAPNIK] resolves to a single-host URL in
 *   some osmdroid builds (`tile.openstreetmap.org` without sub-domains), which
 *   is aggressively rate-limited. The explicit three-subdomain definition below
 *   distributes requests across a/b/c per the OSM tile usage policy.
 * ─────────────────────────────────────────────────────────────────────────────
 */
object MapLayers {

    // ─── OSM Standard (Mapnik) ────────────────────────────────────────────────
    // ► Restored as the FIRST entry — this was the default layer in the original
    //   9ggps release and is now accessible as both the top-of-list choice and
    //   via [fromId] lookup.
    val OSM_STANDARD = XYTileSource(
        "OSMMapnik", 0, 19, 256, ".png",
        arrayOf(
            "https://a.tile.openstreetmap.org/",
            "https://b.tile.openstreetmap.org/",
            "https://c.tile.openstreetmap.org/"
        ),
        "© OpenStreetMap contributors"
    )

    // ─── Carto Voyager ───────────────────────────────────────────────────────
    val CARTO_VOYAGER = XYTileSource(
        "CartoVoyager", 0, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
        ),
        "© OpenStreetMap contributors © CARTO"
    )

    // ─── Carto Positron ──────────────────────────────────────────────────────
    val CARTO_POSITRON = XYTileSource(
        "CartoPositron", 0, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/light_all/",
            "https://b.basemaps.cartocdn.com/light_all/",
            "https://c.basemaps.cartocdn.com/light_all/",
            "https://d.basemaps.cartocdn.com/light_all/"
        ),
        "© OpenStreetMap contributors © CARTO"
    )

    // ─── Carto Dark Matter ───────────────────────────────────────────────────
    val CARTO_DARK = XYTileSource(
        "CartoDark", 0, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/",
            "https://d.basemaps.cartocdn.com/dark_all/"
        ),
        "© OpenStreetMap contributors © CARTO"
    )

    // ─── Named-layer descriptor ───────────────────────────────────────────────

    data class NamedLayer(
        val label: String,
        val source: org.osmdroid.tileprovider.tilesource.ITileSource,
        /** Stable string key used for SharedPreferences persistence. */
        val id: String = source.name()
    )

    // ─── Ordered list for all layer pickers ──────────────────────────────────
    // OSM Standard is listed FIRST — it is the original 9ggps v1 default.
    val ALL: List<NamedLayer> = listOf(
        NamedLayer("OSM Standard",       OSM_STANDARD,   "OSMMapnik"),
        NamedLayer("Voyager (Organic)",   CARTO_VOYAGER,  "CartoVoyager"),
        NamedLayer("Positron (Clean)",    CARTO_POSITRON, "CartoPositron"),
        NamedLayer("Dark Matter",         CARTO_DARK,     "CartoDark")
    )

    /** Resolve a persisted layer id back to a [NamedLayer]. Falls back to OSM Standard. */
    fun fromId(id: String): NamedLayer =
        ALL.firstOrNull { it.id == id } ?: ALL.first()
}
