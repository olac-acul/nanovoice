package dev.nanovoice.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.nanovoice.R

/** Comfortaa (SIL OFL, docs/licenses/Comfortaa-OFL.txt): its lowercase "n" is the same rounded arch as the icon's. */
private val comfortaa = FontFamily(
    Font(R.font.comfortaa, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** The app's name as a wordmark, all lowercase so it starts with the icon's "n". */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: TextUnit = 26.sp) {
    Text(
        stringResource(R.string.wordmark),
        style = TextStyle(fontFamily = comfortaa, fontWeight = FontWeight.Bold, fontSize = size, letterSpacing = 0.2.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
