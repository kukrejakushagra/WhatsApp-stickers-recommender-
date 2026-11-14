package app.stickersearch.ui.kds

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.stickersearch.R
import app.stickersearch.ui.kds.theme.Kds
import app.stickersearch.ui.kds.theme.KdsRadius
import app.stickersearch.ui.kds.theme.KdsSize

/** The main field: a 48px glass capsule with a search icon, placeholder and clear button. */
@Composable
fun KdsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** 48 dp normally; 40 dp on short screens (phones in landscape). */
    height: androidx.compose.ui.unit.Dp = KdsSize.controlXl,
) {
    val c = Kds.colors
    val shape = CircleShape
    Row(
        modifier.fillMaxWidth().height(height).glass(Glass.PANEL, shape, elevation = 4.dp).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KdsIcon(R.drawable.ic_kds_search, 16.dp, c.mutedForeground)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = Kds.type.bodyLg.copy(fontWeight = Kds.type.body.fontWeight), color = c.mutedForeground, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Kds.type.bodyLg.copy(color = c.foreground, fontWeight = Kds.type.body.fontWeight),
                cursorBrush = SolidColor(c.brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(KdsSize.controlSm).clip(CircleShape).clickable { onValueChange("") }
                    .semantics { contentDescription = "Clear" },
                contentAlignment = Alignment.Center,
            ) { KdsIcon(R.drawable.ic_kds_x, 16.dp, c.mutedForeground) }
        }
    }
}

/** Labelled text field (radius-lg glass); single line or a short multi-line area. */
@Composable
fun KdsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    minLines: Int = 1,
) {
    val c = Kds.colors
    val shape = RoundedCornerShape(KdsRadius.lg)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Kds.type.bodyStrong, color = c.foreground)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            minLines = minLines,
            maxLines = if (minLines > 1) 6 else 1,
            textStyle = Kds.type.body.copy(color = c.foreground),
            cursorBrush = SolidColor(c.brand),
            modifier = Modifier.fillMaxWidth().heightIn(min = KdsSize.controlMd).glass(Glass.CONTROL, shape)
                .border(1.dp, c.border, shape).padding(horizontal = 14.dp, vertical = 10.dp),
        )
        hint?.let { Text(it, style = Kds.type.caption, color = c.mutedForeground) }
    }
}
