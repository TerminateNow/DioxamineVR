package io.github.rhythmcache.dioxamine.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * App-specific icons drawn in the Material "Filled" style (24x24 grid, solid shapes,
 * rounded corners) so they sit seamlessly next to the stock Material icons.
 */
object DioxamineIcons {

    /** VR headset (front view): two rounded lenses and a nose bridge. */
    val VrHeadset: ImageVector by lazy {
        icon(
            "VrHeadset",
            "M5 6h14a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3h-3.4c-.6 0-1.1-.3-1.4-.9l-.8-1.5" +
                "c-.55-1.05-2.25-1.05-2.8 0l-.8 1.5c-.3.6-.8.9-1.4.9H5a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z" +
                "m2.5 3h1a2 2 0 0 1 0 4h-1a2 2 0 0 1 0-4z" +
                "m8 0h1a2 2 0 0 1 0 4h-1a2 2 0 0 1 0-4z" to true
        )
    }

    /** Stream stats: magnifying glass looking over a bar graph. */
    val StreamStats: ImageVector by lazy {
        icon(
            "StreamStats",
            // lens ring
            "M10 2.5a7.5 7.5 0 1 1 0 15 7.5 7.5 0 0 1 0-15z" +
                "m0 2.1a5.4 5.4 0 1 0 0 10.8 5.4 5.4 0 0 0 0-10.8z" to true,
            // handle
            "M15.2 16.7l1.5-1.5 4.8 4.8a1.06 1.06 0 0 1-1.5 1.5z" to false,
            // bars
            "M7.5 10.6a.9.9 0 0 1 .9.9v1.1a.9.9 0 0 1-1.8 0v-1.1a.9.9 0 0 1 .9-.9z" to false,
            "M10 7.4a.9.9 0 0 1 .9.9v4.3a.9.9 0 0 1-1.8 0V8.3a.9.9 0 0 1 .9-.9z" to false,
            "M12.5 9.1a.9.9 0 0 1 .9.9v2.6a.9.9 0 0 1-1.8 0V10a.9.9 0 0 1 .9-.9z" to false,
        )
    }

    private fun icon(name: String, vararg paths: Pair<String, Boolean>): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            paths.forEach { (d, evenOdd) ->
                addPath(
                    pathData = addPathNodes(d),
                    pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = SolidColor(Color.Black)
                )
            }
        }.build()
}
