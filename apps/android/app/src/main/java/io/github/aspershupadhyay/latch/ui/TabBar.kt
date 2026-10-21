// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/**
 * The tab bar: icon and word on every tab (labelled icons are recognised
 * faster than bare ones). The chosen tab sits on a soft ember capsule, as in
 * Android's own navigation bar, so a child can see where they are.
 * [badge] counts AI apps waiting on the AI apps tab.
 */
@Composable
fun LatchTabBar(current: Tab, onSelect: (Tab) -> Unit, badge: Int) {
    val signal = LocalSignal.current
    Column(Modifier.fillMaxWidth().background(signal.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(signal.border))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { t ->
                val selected = t == current
                val fg by animateColorAsState(if (selected) signal.text else signal.text2, spring(stiffness = Spring.StiffnessMediumLow), label = "tabFg")
                val icon by animateColorAsState(if (selected) signal.accent else signal.text2, spring(stiffness = Spring.StiffnessMediumLow), label = "tabIcon")
                val wash by animateColorAsState(if (selected) signal.accent.copy(alpha = 0.14f) else Color.Transparent, spring(stiffness = Spring.StiffnessMediumLow), label = "tabWash")
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(t) })
                        .semantics { contentDescription = if (t == Tab.CONNECT && badge > 0) "${t.label}, $badge waiting" else t.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(width = 56.dp, height = 32.dp).clip(RoundedCornerShape(16.dp)).background(wash), contentAlignment = Alignment.Center) {
                        Icon(t.icon, contentDescription = null, tint = icon, modifier = Modifier.size(22.dp))
                        if (t == Tab.CONNECT && badge > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 1.dp).size(18.dp).clip(CircleShape).background(signal.danger),
                                contentAlignment = Alignment.Center,
                            ) { Text("$badge", style = MaterialTheme.typography.labelSmall, color = signal.onAccent) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = fg,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}
