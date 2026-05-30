package com.retrocast.console

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrocast.emulator.NativeRetro
import kotlin.math.*

@Composable
fun TouchpadController(
    nativeRetro: NativeRetro,
    hapticManager: HapticFeedbackManager,
    isLandscape: Boolean
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isNarrow = maxWidth < 360.dp
        val dpadSize = if (isLandscape) 140.dp else if (isNarrow) 120.dp else 150.dp
        val sidePad = if (isLandscape) 32.dp else if (isNarrow) 4.dp else 8.dp
        val bottomPad = if (isLandscape) 0.dp else if (isNarrow) 16.dp else 24.dp
        val clusterSpacing = if (isNarrow) 8.dp else 16.dp

        // --- LEFT CLUSTER (L + DPAD) ---
        Column(
            modifier = Modifier
                .align(if (isLandscape) Alignment.CenterStart else Alignment.BottomStart)
                .padding(start = sidePad, bottom = bottomPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(clusterSpacing)
        ) {
            CircleControlButton(label = "L") { pressed ->
                nativeRetro.setButton(10, pressed)
                if (pressed) hapticManager.triggerTick()
            }

            var pressedButtons by remember { mutableStateOf(setOf<Int>()) }
            val haptic = hapticManager

            GamepadBase(
                modifier = Modifier
                    .size(dpadSize)
                    .clip(CircleShape)
                    .pointerInput(nativeRetro, haptic) {
                        awaitEachGesture {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val center = Offset(cx, cy)
                            val maxDist = minOf(size.width, size.height) / 2f
                            val deadZone = maxDist * 0.20f

                            fun positionToButtons(pos: Offset): Set<Int> {
                                val dx = pos.x - center.x
                                val dy = pos.y - center.y
                                val dist = sqrt(dx * dx + dy * dy)
                                if (dist < deadZone || dist > maxDist) return emptySet()
                                val angle = (atan2(-dy, dx) * 180f / PI.toFloat() + 360f) % 360f
                                return when {
                                    angle < 22.5f || angle >= 337.5f -> setOf(7)
                                    angle < 67.5f -> setOf(4, 7)
                                    angle < 112.5f -> setOf(4)
                                    angle < 157.5f -> setOf(4, 6)
                                    angle < 202.5f -> setOf(6)
                                    angle < 247.5f -> setOf(5, 6)
                                    angle < 292.5f -> setOf(5)
                                    else -> setOf(5, 7)
                                }
                            }

                            var lastButtons = emptySet<Int>()

                            fun updateButtons(buttons: Set<Int>) {
                                if (buttons == lastButtons) return
                                (lastButtons - buttons).forEach { nativeRetro.setButton(it, false) }
                                (buttons - lastButtons).forEach { nativeRetro.setButton(it, true) }
                                if (buttons.isNotEmpty() && lastButtons.isNotEmpty()) {
                                    haptic.triggerTick()
                                }
                                lastButtons = buttons
                                pressedButtons = buttons
                            }

                            val down = awaitFirstDown()
                            updateButtons(positionToButtons(down.position))

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                updateButtons(positionToButtons(change.position))
                                change.consume()
                            }

                            lastButtons.forEach { nativeRetro.setButton(it, false) }
                            pressedButtons = emptySet()
                        }
                    }
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val r = minOf(maxWidth, maxHeight) / 2
                    val diagR = r - 22.dp
                    val diagComp = diagR * 0.707f
                    val off = r - diagComp - 6.dp

                    DPadCircle(Modifier.align(Alignment.TopCenter).offset(y = 3.dp), isPressed = 4 in pressedButtons)
                    DPadCircle(Modifier.align(Alignment.BottomCenter).offset(y = (-3).dp), isPressed = 5 in pressedButtons)
                    DPadCircle(Modifier.align(Alignment.CenterStart).offset(x = 3.dp), isPressed = 6 in pressedButtons)
                    DPadCircle(Modifier.align(Alignment.CenterEnd).offset(x = (-3).dp), isPressed = 7 in pressedButtons)
                    DiagonalDot(Modifier.align(Alignment.TopStart).offset(x = off, y = off), isPressed = 4 in pressedButtons && 6 in pressedButtons)
                    DiagonalDot(Modifier.align(Alignment.TopEnd).offset(x = -off, y = off), isPressed = 4 in pressedButtons && 7 in pressedButtons)
                    DiagonalDot(Modifier.align(Alignment.BottomStart).offset(x = off, y = -off), isPressed = 5 in pressedButtons && 6 in pressedButtons)
                    DiagonalDot(Modifier.align(Alignment.BottomEnd).offset(x = -off, y = -off), isPressed = 5 in pressedButtons && 7 in pressedButtons)
                }
            }
        }

        // --- RIGHT CLUSTER (R + ACTION) ---
        Column(
            modifier = Modifier
                .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomEnd)
                .padding(end = sidePad, bottom = bottomPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(clusterSpacing)
        ) {
            CircleControlButton(label = "R") { pressed ->
                nativeRetro.setButton(11, pressed)
                if (pressed) hapticManager.triggerTick()
            }

            GamepadBase(modifier = Modifier.size(dpadSize)) {
                ActionCircle("A", Modifier.align(Alignment.Center).offset(x = 22.dp, y = (-22).dp), MaterialTheme.colorScheme.tertiary) { 
                    nativeRetro.setButton(8, it > 0); if (it > 0) hapticManager.triggerClick() 
                }
                ActionCircle("B", Modifier.align(Alignment.Center).offset(x = (-22).dp, y = 22.dp), MaterialTheme.colorScheme.secondary) { 
                    nativeRetro.setButton(0, it > 0); if (it > 0) hapticManager.triggerClick() 
                }
            }
        }

        // --- START/SELECT ICONIC BUTTONS ---
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isLandscape) 16.dp else if (isNarrow) 8.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isNarrow) 16.dp else 24.dp)
        ) {
            IconPillButton(Icons.Default.HorizontalRule) { pressed ->
                nativeRetro.setButton(2, pressed)
                if (pressed) hapticManager.triggerTick()
            }
            IconPillButton(Icons.Default.PlayArrow) { pressed ->
                nativeRetro.setButton(3, pressed)
                if (pressed) hapticManager.triggerTick()
            }
        }
    }
}

@Composable
fun GamepadBase(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun DPadCircle(modifier: Modifier, isPressed: Boolean) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (isPressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f))
    )
}

@Composable
fun DiagonalDot(modifier: Modifier, isPressed: Boolean) {
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(if (isPressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
    )
}

@Composable
fun ActionCircle(label: String, modifier: Modifier, accent: Color, onInput: (Int) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(55.dp)
            .clip(CircleShape)
            .background(if (isPressed) accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true; onInput(1)
                    waitForUpOrCancellation()
                    isPressed = false; onInput(0)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (isPressed) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun CircleControlButton(label: String, onToggle: (Boolean) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(if (isPressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true; onToggle(true)
                    waitForUpOrCancellation()
                    isPressed = false; onToggle(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun IconPillButton(icon: ImageVector, onToggle: (Boolean) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (isPressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true; onToggle(true)
                    waitForUpOrCancellation()
                    isPressed = false; onToggle(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
