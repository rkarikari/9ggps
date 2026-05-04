// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.utils

import org.osmdroid.util.BoundingBox

/**
 * Organic Maps–style regional download catalog.
 *
 * Mirrors the region hierarchy used by Organic Maps (continent → country/state)
 * so users can download entire offline map packs with a single tap instead of
 * manually drawing a bounding box.
 *
 * Each [Region] carries a [BoundingBox] that [OfflineMapManager.downloadRegion]
 * accepts directly; no additional conversion is required.
 *
 * Usage:
 * ```kotlin
 * // Get all continents
 * val continents = OrganicMapRegions.continents
 *
 * // Get countries within a continent
 * val countries = OrganicMapRegions.countriesFor("Africa")
 *
 * // Start download
 * OfflineMapManager.downloadRegion(context, region.box, minZoom, maxZoom, ...)
 * ```
 */
object OrganicMapRegions {

    /**
     * A downloadable map region.
     *
     * @param name          Human-readable region name (shown in the UI).
     * @param continent     Parent continent key — matches [Continent.name].
     * @param box           Geographic bounding box for tile pre-caching.
     * @param tileSizeHint  Rough tile count at zoom 1–16 (informational only;
     *                      the precise count is computed by [OfflineMapManager.estimateTileCount]).
     */
    data class Region(
        val name: String,
        val continent: String,
        val box: BoundingBox,
        val tileSizeHint: String = ""
    )

    data class Continent(val name: String, val emoji: String)

    // ─── Continent list ───────────────────────────────────────────────────────

    val continents: List<Continent> = listOf(
        Continent("Africa",        "🌍"),
        Continent("Asia",          "🌏"),
        Continent("Europe",        "🌍"),
        Continent("North America", "🌎"),
        Continent("South America", "🌎"),
        Continent("Oceania",       "🌏"),
        Continent("Antarctica",    "🧊")
    )

    // ─── Region catalog ───────────────────────────────────────────────────────
    // BoundingBox(latNorth, lonEast, latSouth, lonWest)

    val all: List<Region> = listOf(

        // ── Africa ────────────────────────────────────────────────────────────
        Region("Algeria",             "Africa",   BoundingBox( 37.1,   9.0,  18.9, -8.7), "~L"),
        Region("Angola",              "Africa",   BoundingBox(-4.4,  24.1, -18.0, 11.7), "~M"),
        Region("Cameroon",            "Africa",   BoundingBox(13.1,  16.2,   1.6,  8.5), "~S"),
        Region("Côte d'Ivoire",       "Africa",   BoundingBox(10.7,  -2.5,   4.2, -8.6), "~S"),
        Region("DR Congo",            "Africa",   BoundingBox( 5.4,  31.3, -13.5, 12.2), "~L"),
        Region("Egypt",               "Africa",   BoundingBox(31.7,  37.1,  22.0, 24.7), "~M"),
        Region("Ethiopia",            "Africa",   BoundingBox(14.9,  47.9,   3.4, 33.0), "~M"),
        Region("Ghana",               "Africa",   BoundingBox(11.2,   1.2,   4.7, -3.3), "~S"),
        Region("Kenya",               "Africa",   BoundingBox( 5.0,  41.9,  -4.7, 33.9), "~S"),
        Region("Libya",               "Africa",   BoundingBox(33.2,  25.2,  19.5,  9.3), "~L"),
        Region("Madagascar",          "Africa",   BoundingBox(-11.9, 50.5, -25.6, 43.2), "~M"),
        Region("Mali",                "Africa",   BoundingBox(25.0,   4.3,  10.1, -4.2), "~L"),
        Region("Morocco",             "Africa",   BoundingBox(35.9,  -1.0,  27.7, -13.2), "~M"),
        Region("Mozambique",          "Africa",   BoundingBox(-10.5, 40.8, -26.9, 30.2), "~M"),
        Region("Nigeria",             "Africa",   BoundingBox(13.9,  14.7,   4.3,  2.7), "~M"),
        Region("South Africa",        "Africa",   BoundingBox(-22.1, 33.0, -34.8, 16.5), "~L"),
        Region("Sudan",               "Africa",   BoundingBox(22.2,  38.6,   8.7, 21.8), "~L"),
        Region("Tanzania",            "Africa",   BoundingBox(-1.0,  40.5, -11.7, 29.3), "~M"),
        Region("Tunisia",             "Africa",   BoundingBox(37.5,  11.6,  30.2,  7.5), "~S"),
        Region("Uganda",              "Africa",   BoundingBox( 4.2,  35.0,  -1.5, 29.6), "~S"),
        Region("Zimbabwe",            "Africa",   BoundingBox(-15.6, 33.1, -22.4, 25.2), "~S"),

        // ── Asia ──────────────────────────────────────────────────────────────
        Region("Afghanistan",         "Asia",     BoundingBox(38.5,  74.9,  29.4, 60.5), "~M"),
        Region("Bangladesh",          "Asia",     BoundingBox(26.6,  92.7,  20.7, 88.0), "~S"),
        Region("Cambodia",            "Asia",     BoundingBox(14.7,  107.6,  10.4, 102.3), "~S"),
        Region("China",               "Asia",     BoundingBox(53.6,  135.1,  18.2, 73.5), "~XL"),
        Region("India",               "Asia",     BoundingBox(35.5,   97.4,   8.0, 68.1), "~XL"),
        Region("Indonesia",           "Asia",     BoundingBox( 5.9,  141.0, -11.0, 95.0), "~XL"),
        Region("Iran",                "Asia",     BoundingBox(39.8,   63.3,  25.1, 44.0), "~L"),
        Region("Iraq",                "Asia",     BoundingBox(37.4,   48.6,  29.1, 38.8), "~M"),
        Region("Japan",               "Asia",     BoundingBox(45.6,  145.8,  24.0, 122.9), "~L"),
        Region("Jordan",              "Asia",     BoundingBox(33.4,   39.3,  29.2, 34.9), "~S"),
        Region("Kazakhstan",          "Asia",     BoundingBox(55.4,   87.4,  40.6, 50.3), "~XL"),
        Region("Malaysia",            "Asia",     BoundingBox( 7.4,  119.3,   0.9, 99.6), "~M"),
        Region("Myanmar",             "Asia",     BoundingBox(28.5,  101.2,  10.0, 92.2), "~M"),
        Region("Nepal",               "Asia",     BoundingBox(30.4,   88.2,  26.4, 80.0), "~S"),
        Region("North Korea",         "Asia",     BoundingBox(42.6,  129.6,  37.7, 124.2), "~M"),
        Region("Pakistan",            "Asia",     BoundingBox(37.1,   77.0,  23.6, 60.9), "~L"),
        Region("Philippines",         "Asia",     BoundingBox(21.1,  126.6,   4.6, 116.9), "~M"),
        Region("Saudi Arabia",        "Asia",     BoundingBox(32.2,   55.7,  16.4, 34.5), "~XL"),
        Region("South Korea",         "Asia",     BoundingBox(38.6,  129.6,  33.1, 124.6), "~M"),
        Region("Sri Lanka",           "Asia",     BoundingBox( 9.8,   81.9,   5.9, 79.7), "~S"),
        Region("Syria",               "Asia",     BoundingBox(37.3,   42.4,  32.3, 35.7), "~S"),
        Region("Taiwan",              "Asia",     BoundingBox(25.3,  122.0,  21.9, 120.0), "~S"),
        Region("Thailand",            "Asia",     BoundingBox(20.5,  105.7,   5.6, 97.3), "~M"),
        Region("Turkey",              "Asia",     BoundingBox(42.1,   44.8,  35.8, 26.0), "~L"),
        Region("Uzbekistan",          "Asia",     BoundingBox(45.6,   73.2,  37.2, 55.9), "~M"),
        Region("Vietnam",             "Asia",     BoundingBox(23.4,  109.5,   8.4, 102.1), "~M"),
        Region("Yemen",               "Asia",     BoundingBox(18.9,   54.1,  11.9, 42.5), "~M"),

        // ── Europe ────────────────────────────────────────────────────────────
        Region("Austria",             "Europe",   BoundingBox(48.8,  17.2,  46.4, 9.5), "~S"),
        Region("Belarus",             "Europe",   BoundingBox(53.9,  32.8,  51.3, 23.2), "~M"),
        Region("Belgium",             "Europe",   BoundingBox(51.5,   6.4,  49.5, 2.5), "~XS"),
        Region("Bulgaria",            "Europe",   BoundingBox(44.2,  28.6,  41.2, 22.4), "~S"),
        Region("Croatia",             "Europe",   BoundingBox(46.6,  19.4,  42.4, 13.5), "~S"),
        Region("Czech Republic",      "Europe",   BoundingBox(51.1,  18.9,  48.6, 12.1), "~S"),
        Region("Denmark",             "Europe",   BoundingBox(57.8,  15.2,  54.6,  8.1), "~S"),
        Region("Finland",             "Europe",   BoundingBox(70.1,  31.6,  59.8, 19.1), "~L"),
        Region("France",              "Europe",   BoundingBox(51.1,   9.6,  41.3, -5.1), "~L"),
        Region("Germany",             "Europe",   BoundingBox(55.1,  15.0,  47.3,  5.9), "~L"),
        Region("Greece",              "Europe",   BoundingBox(41.8,  28.2,  35.0, 19.4), "~M"),
        Region("Hungary",             "Europe",   BoundingBox(48.6,  22.9,  45.7, 16.1), "~S"),
        Region("Ireland",             "Europe",   BoundingBox(55.4,  -6.0,  51.4, -10.5), "~S"),
        Region("Italy",               "Europe",   BoundingBox(47.1,  18.6,  36.5,  6.6), "~L"),
        Region("Netherlands",         "Europe",   BoundingBox(53.6,   7.2,  50.8,  3.4), "~S"),
        Region("Norway",              "Europe",   BoundingBox(71.2,  31.1,  57.9,  4.6), "~L"),
        Region("Poland",              "Europe",   BoundingBox(54.8,  24.2,  49.0, 14.1), "~L"),
        Region("Portugal",            "Europe",   BoundingBox(42.2,  -6.2,  36.9, -9.5), "~S"),
        Region("Romania",             "Europe",   BoundingBox(48.3,  29.7,  43.6, 20.3), "~M"),
        Region("Russia (European)",   "Europe",   BoundingBox(69.1,  60.0,  41.2, 27.3), "~XL"),
        Region("Russia (Siberia)",    "Asia",     BoundingBox(72.0, 179.9,  49.0, 60.0), "~XL"),
        Region("Serbia",              "Europe",   BoundingBox(46.2,  23.0,  42.2, 18.8), "~S"),
        Region("Slovakia",            "Europe",   BoundingBox(49.6,  22.6,  47.7, 16.8), "~S"),
        Region("Spain",               "Europe",   BoundingBox(43.8,   4.3,  36.0, -9.3), "~L"),
        Region("Sweden",              "Europe",   BoundingBox(69.1,  24.2,  55.3,  11.1), "~L"),
        Region("Switzerland",         "Europe",   BoundingBox(47.8,  10.5,  45.8,  6.0), "~S"),
        Region("Ukraine",             "Europe",   BoundingBox(52.4,  40.2,  44.4, 22.1), "~L"),
        Region("United Kingdom",      "Europe",   BoundingBox(60.9,   1.8,  49.9, -8.6), "~L"),

        // ── North America ─────────────────────────────────────────────────────
        Region("Canada",              "North America", BoundingBox(83.1, -52.6, 41.7, -141.0), "~XL"),
        Region("Cuba",                "North America", BoundingBox(23.2, -74.1, 19.8,  -85.0), "~S"),
        Region("Guatemala",           "North America", BoundingBox(17.8, -88.2, 13.7,  -92.2), "~XS"),
        Region("Haiti",               "North America", BoundingBox(20.1, -71.6, 18.0,  -74.5), "~XS"),
        Region("Honduras",            "North America", BoundingBox(16.5, -83.2, 12.9,  -89.4), "~S"),
        Region("Jamaica",             "North America", BoundingBox(18.5, -76.2, 17.7,  -78.4), "~XS"),
        Region("Mexico",              "North America", BoundingBox(32.7, -86.7, 14.5, -117.1), "~XL"),
        Region("USA — Contiguous",    "North America", BoundingBox(49.4, -66.9, 24.4, -124.8), "~XL"),
        Region("USA — Alaska",        "North America", BoundingBox(71.4,-129.9, 54.7, -168.1), "~XL"),
        Region("USA — Hawaii",        "North America", BoundingBox(22.3,-154.8, 18.9, -160.3), "~S"),

        // ── South America ─────────────────────────────────────────────────────
        Region("Argentina",           "South America", BoundingBox(-21.8, -53.6, -55.1,  -73.6), "~XL"),
        Region("Bolivia",             "South America", BoundingBox(-9.7, -57.5, -22.9,  -69.7), "~M"),
        Region("Brazil",              "South America", BoundingBox( 5.3, -34.8, -33.8,  -73.9), "~XL"),
        Region("Chile",               "South America", BoundingBox(-17.5, -66.4, -55.9,  -75.7), "~L"),
        Region("Colombia",            "South America", BoundingBox(13.4, -66.8, -4.2,   -81.8), "~M"),
        Region("Ecuador",             "South America", BoundingBox( 1.5, -75.2, -5.0,   -81.1), "~S"),
        Region("Paraguay",            "South America", BoundingBox(-19.3, -54.3, -27.6,  -62.7), "~S"),
        Region("Peru",                "South America", BoundingBox( -0.0, -68.7, -18.4,  -81.3), "~M"),
        Region("Uruguay",             "South America", BoundingBox(-30.1, -53.1, -34.9,  -58.4), "~S"),
        Region("Venezuela",           "South America", BoundingBox(12.2, -59.8,   0.6,  -73.4), "~M"),

        // ── Oceania ───────────────────────────────────────────────────────────
        Region("Australia",           "Oceania",  BoundingBox(-10.0,  153.6, -43.7, 113.2), "~XL"),
        Region("Fiji",                "Oceania",  BoundingBox(-15.7,  180.0, -19.2, 177.1), "~XS"),
        Region("New Zealand",         "Oceania",  BoundingBox(-34.4,  178.6, -47.3, 166.4), "~M"),
        Region("Papua New Guinea",    "Oceania",  BoundingBox( -1.3,  150.2, -11.6, 140.8), "~M"),

        // ── Antarctica ────────────────────────────────────────────────────────
        Region("Antarctica",          "Antarctica", BoundingBox(-60.0,  180.0, -90.0, -180.0), "~XL")
    )

    /** Return only the regions belonging to [continent]. */
    fun countriesFor(continent: String): List<Region> =
        all.filter { it.continent == continent }

    /**
     * Friendly tile-count category labels shown in the download UI.
     * ("XS" ≈ city, "S" ≈ small country, "M" ≈ medium, "L" ≈ large, "XL" ≈ continental)
     */
    fun sizeLabel(hint: String): String = when (hint) {
        "~XS" -> "< 50 MB"
        "~S"  -> "50–200 MB"
        "~M"  -> "200–500 MB"
        "~L"  -> "500 MB–1 GB"
        "~XL" -> "> 1 GB"
        else  -> "Unknown"
    }
}
