package dev.borescope.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few Material icons we use, as 24dp vectors. This replaces `material-icons-extended`,
 * whose thousands of icon classes added ~50 MB to unminified (debug/nightly) builds.
 * Path data is from the Material icon set (Apache 2.0).
 */
object AppIcons {
    private fun icon(name: String, autoMirror: Boolean = false, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    val RotateRight = icon(
        "RotateRight", autoMirror = true,
        path = "M15.55,5.55L11,1v3.07C7.06,4.56 4,7.92 4,12s3.05,7.44 7,7.93v-2.02c-2.84,-0.48 -5,-2.94 -5,-5.91s2.16,-5.43 5,-5.91V10l4.55,-4.45zM19.93,11c-0.17,-1.39 -0.72,-2.73 -1.62,-3.89l-1.42,1.42c0.54,0.75 0.88,1.6 1.02,2.47h2.02zM13,17.9v2.02c1.39,-0.17 2.74,-0.71 3.9,-1.61l-1.44,-1.44c-0.75,0.54 -1.59,0.89 -2.46,1.03zM16.89,15.48l1.42,1.41c0.9,-1.16 1.45,-2.5 1.62,-3.89h-2.02c-0.14,0.87 -0.48,1.72 -1.02,2.48z",
    )
    val Flip = icon(
        "Flip",
        path = "M15,21h2v-2h-2V21zM19,9h2V7h-2V9zM3,5v14c0,1.1 0.9,2 2,2h4v-2H5V5h4V3H5C3.9,3 3,3.9 3,5zM19,3v2h2C21,3.9 20.1,3 19,3zM11,23h2V1h-2V23zM19,17h2v-2h-2V17zM15,5h2V3h-2V5zM19,13h2v-2h-2V13zM19,21c1.1,0 2,-0.9 2,-2h-2V21z",
    )
    val PhotoCamera = icon(
        "PhotoCamera",
        path = "M12,12m-3.2,0a3.2,3.2 0 1,0 6.4,0a3.2,3.2 0 1,0 -6.4,0M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
    )
    val Stop = icon("Stop", path = "M6,6h12v12H6z")
    val FiberManualRecord = icon(
        "FiberManualRecord",
        path = "M12,12m-8,0a8,8 0 1,0 16,0a8,8 0 1,0 -16,0",
    )
    val Tune = icon(
        "Tune",
        path = "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z",
    )
}
