// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aspershupadhyay.latch.ui.theme.LocalSignal

/**
 * The tab bar: a floating capsule. The chosen tab grows into an ink pill with
 * its name; the others are icons. [badge] counts AI apps waiting on Connect.
 */
@Composable
fun LatchTabBar(current: Tab, onSelect: (Tab) -> Unit, badge: Int) {
    val signal = LocalSignal.current
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .shadow(18.dp, CircleShape, ambientColor = signal.text.copy(alpha = 0.25f), spotColor = signal.text.copy(alpha = 0.25f))
                .clip(CircleShape)
                .background(signal.surface)
                .border(1.dp, signal.border.copy(alpha = if (signal.dark) 1f else 0.6f), CircleShape)
                .padding(6.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                val selected = t == current
                val bg by animateColorAsState(if (selected) signal.ink else signal.surface, spring(stiffness = Spring.StiffnessMediumLow), label = "tabBg")
                val fg by animateColorAsState(if (selected) signal.onInk else signal.text2, spring(stiffness = Spring.StiffnessMediumLow), label = "tabFg")
                Row(
                    Modifier
                        .height(48.dp)
                        .clip(CircleShape)
                        .background(bg)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(t) })
                        .semantics { contentDescription = if (t == Tab.CONNECT && badge > 0) "${t.label}, $badge waiting" else t.label }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Icon(t.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
                        if (t == Tab.CONNECT && badge > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-4).dp).size(16.dp).clip(CircleShape).background(signal.accent),
                                contentAlignment = Alignment.Center,
                            ) { Text("$badge", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing), color = signal.onInk) }
                        }
                    }
                    AnimatedVisibility(
                        selected,
                        enter = fadeIn() + expandHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)),
                        exit = fadeOut() + shrinkHorizontally(spring(stiffness = Spring.StiffnessMedium)),
                    ) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Text(t.label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
