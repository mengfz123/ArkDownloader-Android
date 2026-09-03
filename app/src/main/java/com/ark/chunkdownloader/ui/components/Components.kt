package com.ark.chunkdownloader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ark.chunkdownloader.ui.theme.ArkBg
import com.ark.chunkdownloader.ui.theme.ArkBorder
import com.ark.chunkdownloader.ui.theme.ArkCard
import com.ark.chunkdownloader.ui.theme.ArkCard2
import com.ark.chunkdownloader.ui.theme.ArkDimens
import com.ark.chunkdownloader.ui.theme.ArkMuted
import com.ark.chunkdownloader.ui.theme.ArkPrimary
import com.ark.chunkdownloader.ui.theme.ArkPrimarySoft
import com.ark.chunkdownloader.ui.theme.ArkSub
import com.ark.chunkdownloader.ui.theme.ArkText

@Composable
fun SurfacePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArkDimens.formCardRadius))
            .background(ArkCard)
            .border(1.dp, ArkBorder, RoundedCornerShape(ArkDimens.formCardRadius))
            .padding(ArkDimens.formCardPad),
        content = content
    )
}

@Composable
fun SectionTitle(title: String) {
    Text(
        title,
        color = ArkText,
        fontSize = ArkDimens.sectionTitle,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
fun FieldLabel(text: String, required: Boolean = false) {
    Row(Modifier.padding(top = ArkDimens.labelTop, bottom = ArkDimens.labelBottom)) {
        Text(text, color = ArkMuted, fontSize = ArkDimens.labelText)
        if (required) {
            Text(" *", color = Color(0xFFF07178), fontSize = ArkDimens.labelText)
        }
    }
}

@Composable
fun ArkField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    placeholder: String? = null,
    required: Boolean = false
) {
    Column(modifier.fillMaxWidth()) {
        FieldLabel(label, required)
        val shape = RoundedCornerShape(ArkDimens.inputRadius)
        val minH = when {
            singleLine && minLines <= 1 -> ArkDimens.inputHeight
            minLines >= 5 -> ArkDimens.urlAreaH
            else -> ArkDimens.pathAreaMinH
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minH)
                .then(if (singleLine && minLines <= 1) Modifier.height(ArkDimens.inputHeight) else Modifier)
                .clip(shape)
                .background(ArkBg)
                .border(1.dp, ArkBorder, shape)
                .padding(horizontal = 8.dp, vertical = if (singleLine && minLines <= 1) 0.dp else 7.dp),
            contentAlignment = if (singleLine && minLines <= 1) Alignment.CenterStart else Alignment.TopStart
        ) {
            if (value.isEmpty() && !placeholder.isNullOrBlank()) {
                Text(placeholder, color = ArkMuted, fontSize = ArkDimens.inputText)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                textStyle = TextStyle(
                    color = ArkText,
                    fontSize = ArkDimens.inputText,
                    lineHeight = 17.sp
                ),
                cursorBrush = SolidColor(ArkPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!singleLine || minLines > 1) Modifier.heightIn(min = minH - 14.dp) else Modifier)
            )
        }
    }
}

@Composable
fun CompactSearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .height(ArkDimens.searchHeight)
            .clip(RoundedCornerShape(ArkDimens.searchRadius))
            .background(ArkBg)
            .border(1.dp, ArkBorder, RoundedCornerShape(ArkDimens.searchRadius))
            .padding(horizontal = ArkDimens.searchPadH),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = ArkMuted, fontSize = ArkDimens.searchText)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = ArkText, fontSize = ArkDimens.searchText),
            cursorBrush = SolidColor(ArkPrimary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun ArkSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    onChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    Column(Modifier.fillMaxWidth()) {
        FieldLabel(title)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = ArkPrimary,
                    activeTrackColor = ArkPrimary,
                    inactiveTrackColor = ArkCard2
                )
            )
            Text(
                valueLabel,
                color = ArkPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = ArkDimens.inputText,
                modifier = Modifier.width(ArkDimens.sliderValW)
            )
        }
    }
}

@Composable
fun ArkSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ArkBorder))
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 32.dp)
                .padding(vertical = ArkDimens.switchPadV),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                color = ArkText,
                fontSize = ArkDimens.switchLabel
            )
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                modifier = Modifier.scale(0.78f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = ArkPrimary,
                    uncheckedThumbColor = ArkSub,
                    uncheckedTrackColor = ArkCard2,
                    uncheckedBorderColor = ArkBorder
                )
            )
        }
    }
}

@Composable
fun PillButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    ghost: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        primary && !ghost -> ArkPrimarySoft
        danger -> Color(0x1AF07178)
        ghost -> Color.Transparent
        else -> ArkCard2
    }
    val fg = when {
        primary -> ArkPrimary
        danger -> Color(0xFFF07178)
        ghost -> ArkMuted
        else -> ArkText
    }
    val border = if (ghost) ArkBorder else Color.Transparent
    Box(
        modifier
            .clip(RoundedCornerShape(ArkDimens.actionRadius))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(ArkDimens.actionRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = ArkDimens.actionPadH, vertical = ArkDimens.actionPadV)
    ) {
        Text(
            label,
            color = fg,
            fontSize = ArkDimens.actionText,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun ToolbarChip(
    label: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(ArkDimens.toolBtnRadius))
            .background(if (primary) ArkPrimary else ArkCard2)
            .border(1.dp, if (primary) ArkPrimary else ArkBorder, RoundedCornerShape(ArkDimens.toolBtnRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = ArkDimens.toolBtnPadH, vertical = ArkDimens.toolBtnPadV)
    ) {
        Text(
            label,
            color = if (primary) Color.White else ArkText,
            fontSize = ArkDimens.toolBtnText,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun HeaderPill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ArkCard)
            .border(1.dp, ArkBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = ArkDimens.headerPillPadH, vertical = ArkDimens.headerPillPadV)
    ) {
        Text(label, color = ArkText, fontSize = ArkDimens.headerPillText)
    }
}

@Composable
fun StatusBadge(text: String, color: Color, soft: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(soft)
            .padding(horizontal = ArkDimens.badgePadH, vertical = ArkDimens.badgePadV)
    ) {
        Text(text, color = color, fontSize = ArkDimens.badgeText, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun TagChip(text: String, muted: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(ArkDimens.tagRadius))
            .background(if (muted) ArkCard2 else ArkPrimarySoft)
            .padding(horizontal = ArkDimens.tagPadH, vertical = ArkDimens.tagPadV)
    ) {
        Text(
            text,
            color = if (muted) ArkMuted else ArkPrimary,
            fontSize = ArkDimens.tagText
        )
    }
}

@Composable
fun PrimaryDockButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(ArkDimens.dockBtnH),
        shape = RoundedCornerShape(ArkDimens.dockBtnRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = ArkPrimary,
            contentColor = Color.White,
            disabledContainerColor = ArkPrimary.copy(alpha = 0.45f),
            disabledContentColor = Color.White.copy(alpha = 0.7f)
        ),
        contentPadding = PaddingValues(0.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp)
    ) {
        Text(label, fontSize = ArkDimens.dockBtnText, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun RpcDot(online: Boolean) {
    Box(
        Modifier
            .size(ArkDimens.rpcDot)
            .clip(CircleShape)
            .background(if (online) Color(0xFF3ECF8E) else ArkMuted)
    )
}
