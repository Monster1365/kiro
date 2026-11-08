package com.smishingguard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smishingguard.domain.RiskLevel

data class RiskStyle(val fg: Color, val bg: Color, val icon: ImageVector)

/** 색상만으로 구분하지 않도록 아이콘 + 글자 라벨을 함께 쓴다. 모두 높은 대비 조합. */
fun riskStyle(level: RiskLevel): RiskStyle = when (level) {
    RiskLevel.HIGH -> RiskStyle(Color(0xFF8C1008), Color(0xFFFFE9E6), Icons.Filled.Warning)
    RiskLevel.CAUTION -> RiskStyle(Color(0xFF6B4100), Color(0xFFFFF1D6), Icons.Filled.Search)
    RiskLevel.LOW -> RiskStyle(Color(0xFF14521A), Color(0xFFE3F4E4), Icons.Filled.CheckCircle)
    RiskLevel.UNKNOWN -> RiskStyle(Color(0xFF2B3A42), Color(0xFFECEFF1), Icons.Filled.Info)
}

@Composable
fun RiskCard(level: RiskLevel, summary: String, modifier: Modifier = Modifier) {
    val st = riskStyle(level)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "위험 수준 ${level.label}. ${level.headline}. $summary" },
        colors = CardDefaults.cardColors(containerColor = st.bg),
        border = BorderStroke(2.dp, st.fg),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(st.icon, contentDescription = null, tint = st.fg, modifier = Modifier.size(40.dp))
                Text("위험 수준: ${level.label}", style = MaterialTheme.typography.titleMedium, color = st.fg)
            }
            Text(level.headline, style = MaterialTheme.typography.titleLarge, color = st.fg)
            if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF111111))
        }
    }
}

/** 최소 높이 64dp 의 큰 버튼 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    danger: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    val m = modifier.fillMaxWidth().heightIn(min = 64.dp)
    if (secondary) {
        OutlinedButton(onClick = onClick, modifier = m, enabled = enabled, shape = shape) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        Button(
            onClick = onClick, modifier = m, enabled = enabled, shape = shape,
            colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color(0xFFB0B7BD)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun BulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun VSpace(h: Int = 12) = Spacer(Modifier.height(h.dp))

@Composable
fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // 색 + 글자(켜짐/꺼짐 등)로 함께 표시
        Text(
            (if (ok) "✔ " else "– ") + value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (ok) Color(0xFF14521A) else Color(0xFF4A4A4A),
        )
    }
}
