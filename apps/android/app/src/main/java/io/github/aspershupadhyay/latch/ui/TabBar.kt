// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import io.github.aspershupadhyay.latch.session.SessionState
import io.github.aspershupadhyay.latch.ui.theme.OnStop
import io.github.aspershupadhyay.latch.ui.theme.StopRed
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
 * Android's own navigation bar, so a child can see where they are; [current]
 * is null while a page (Settings, AI apps) is open over the tabs.
 * [badge] counts AI apps waiting to connect, on Home, where they are answered.
 */
@Composable
fun LatchTabBar(current: Tab?, onSelect: (Tab) -> Unit, badge: Int) {
    val signal = LocalSignal.current
    Column(Modifier.fillMaxWidth().background(signal.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(signal.border))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp).selectableGroup(),
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
                        .semantics { contentDescription = if (t == Tab.HOME && badge > 0) "${t.label}, $badge AI ${if (badge == 1) "app" else "apps"} waiting" else t.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(width = 64.dp, height = 34.dp).clip(RoundedCornerShape(17.dp)).background(wash), contentAlignment = Alignment.Center) {
                        Icon(t.icon, contentDescription = null, tint = icon, modifier = Modifier.size(22.dp))
                        if (t == Tab.HOME && badge > 0) {
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

/**
 * While a session runs, a slim bar above the tabs on every screen but Home:
 * what the AI may do now, in words, and Stop. The app stays usable underneath,
 * and stopping never needs a trip back to Home. Tapping the words opens Home.
 */
@Composable
fun SessionBar(session: SessionState, waiting: Boolean, reducedMotion: Boolean, stop: () -> Unit, open: () -> Unit) {
    val live = session is SessionState.Active || session is SessionState.Reconnecting || session is SessionState.Connecting
    AnimatedVisibility(
        visible = live,
        enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
    ) {
        val signal = LocalSignal.current
        val (tint, words) = when {
            waiting -> signal.warning to "Your answer is needed"
            session is SessionState.Active && session.paused -> signal.text2 to "Paused"
            session is SessionState.Active -> signal.live to "Your AI can work now"
            session is SessionState.Reconnecting -> signal.warning to "Reconnecting"
            else -> signal.text2 to "Connecting…"
        }
        Column(Modifier.fillMaxWidth().background(signal.surface)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(signal.border))
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = "Open Home", role = Role.Button, onClick = open)
                        .padding(horizontal = 10.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
                    Spacer(Modifier.width(10.dp))
                    Text(words, style = MaterialTheme.typography.titleSmall, color = signal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = stop,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StopRed, contentColor = OnStop),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Stop everything" },
                ) {
                    Icon(LatchIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
