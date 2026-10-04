// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.unit.sp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/**
 * The tab bar: a flat terminal strip with a hairline top edge. Every tab shows
 * its icon and a mono caption; the chosen one lights up in ember with a short
 * ember rule above it. [badge] counts AI apps waiting on Connect.
 */
@Composable
fun LatchTabBar(current: Tab, onSelect: (Tab) -> Unit, badge: Int) {
    val signal = LocalSignal.current
    Column(Modifier.fillMaxWidth().background(signal.canvas)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(signal.border))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { t ->
                val selected = t == current
                val fg by animateColorAsState(if (selected) signal.text else signal.text2, spring(stiffness = Spring.StiffnessMediumLow), label = "tabFg")
                val icon by animateColorAsState(if (selected) signal.accent else signal.text2, spring(stiffness = Spring.StiffnessMediumLow), label = "tabIcon")
                val rule by animateDpAsState(if (selected) 22.dp else 0.dp, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "tabRule")
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(t) })
                        .semantics { contentDescription = if (t == Tab.CONNECT && badge > 0) "${t.label}, $badge waiting" else t.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(width = rule, height = 2.dp).background(signal.accent))
                    Spacer(Modifier.height(10.dp))
                    Box {
                        Icon(t.icon, contentDescription = null, tint = icon, modifier = Modifier.size(22.dp))
                        if (t == Tab.CONNECT && badge > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp).size(16.dp).clip(RoundedCornerShape(4.dp)).background(signal.accent),
                                contentAlignment = Alignment.Center,
                            ) { Text("$badge", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing), color = signal.onInk) }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        t.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.9.sp),
                        color = fg,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}
