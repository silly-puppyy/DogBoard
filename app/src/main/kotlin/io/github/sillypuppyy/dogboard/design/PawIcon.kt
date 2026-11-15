package io.github.sillypuppyy.dogboard.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/**
 * Kb:202, 274, 927: the canvas's traced paw (viewBox 103 x 98), drawn where the design shows the
 * `pets` icon: the Dog theme's return key (22 x 21) and the emoji Animals category (18 x 17).
 */
@Composable
fun PawIcon(width: Dp, height: Dp, color: Color, modifier: Modifier = Modifier) {
    val path = remember { PathParser().parsePathString(PAW).toPath() }
    Canvas(modifier.size(width, height)) {
        scale(size.width / 103f, size.height / 98f, pivot = androidx.compose.ui.geometry.Offset.Zero) { drawPath(path, color) }
    }
}

private const val PAW = "M34.7 97.0 C35.7 96.9 38.6 96.4 41.2 96.0 C49.8 94.5 51.7 94.5 60.4 96.0 C67.4 97.3 71.0 97.5 73.9 97.0 C81.1 95.7 86.1 91.2 87.7 84.7 C88.1 83.1 88.0 79.1 87.5 77.4 C86.3 72.9 83.8 69.2 79.8 66.1 C72.5 60.4 71.0 58.9 67.0 53.0 C64.3 49.1 62.4 46.9 60.2 45.5 C53.2 40.8 44.5 41.6 38.6 47.5 C37.1 49.0 36.3 50.0 34.7 52.3 C34.2 53.0 33.4 54.1 32.9 54.9 C30.1 58.8 28.0 60.9 22.3 65.4 C17.4 69.2 15.0 72.5 13.8 77.1 C11.3 86.5 17.3 95.4 27.3 97.0 C29.8 97.4 31.6 97.4 34.7 97.0Z M18.2 62.2 C24.3 60.6 27.2 52.2 24.4 44.0 C23.5 41.3 20.5 35.5 18.8 33.1 C14.9 27.8 10.5 27.3 6.2 31.6 C1.9 35.9 0.0 43.1 1.7 49.0 C2.1 50.4 3.5 53.4 4.5 54.8 C8.3 60.5 13.7 63.4 18.2 62.2Z M88.5 62.2 C94.2 60.7 99.9 53.8 101.0 47.0 C102.6 37.5 95.1 26.8 88.5 29.0 C85.7 29.9 83.1 33.0 80.1 39.1 C77.6 44.0 77.0 46.0 76.9 49.6 C76.8 54.1 77.9 57.5 80.3 59.9 C82.6 62.2 85.4 62.9 88.5 62.2Z M37.9 42.1 C44.2 40.5 48.9 32.7 48.9 23.8 C48.9 16.0 47.5 9.4 45.1 6.3 C40.4 0.0 29.0 4.9 24.2 15.1 C22.3 19.0 21.9 23.3 23.0 28.4 C24.9 37.9 31.3 43.8 37.9 42.1Z M69.3 42.0 C77.2 39.7 82.2 26.8 78.9 16.9 C76.0 8.2 66.5 1.6 60.2 4.0 C56.1 5.5 53.9 11.5 53.5 22.2 C53.4 25.9 53.7 28.6 54.8 31.6 C57.3 39.4 63.4 43.7 69.3 42.0Z"
