package com.photonlab.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.photonlab.ui.editor.ToolCategory

@Composable
fun CategoryTabs(
    selected: ToolCategory,
    onSelect: (ToolCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        ToolCategory.entries.forEach { cat ->
            FilterChip(
                selected = cat == selected,
                onClick = { onSelect(cat) },
                label = { Text(cat.label) },
                leadingIcon = { Icon(imageVector = cat.icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
            )
        }
    }
}

val ToolCategory.label: String
    get() = when (this) {
        ToolCategory.TONE -> "Tone"
        ToolCategory.COLOR -> "Color"
        ToolCategory.LUT -> "LUT"
        ToolCategory.TRANSFORM -> "Transform"
    }

val ToolCategory.icon: ImageVector
    get() = when (this) {
        ToolCategory.TONE -> Icons.Default.Tune
        ToolCategory.COLOR -> Icons.Default.Palette
        ToolCategory.LUT -> Icons.Default.AutoFixHigh
        ToolCategory.TRANSFORM -> Icons.Default.Crop
    }
