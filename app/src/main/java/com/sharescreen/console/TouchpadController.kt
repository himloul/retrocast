package com.sharescreen.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.sharescreen.emulator.NativeRetro

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

            GamepadBase(modifier = Modifier.size(dpadSize)) {
                DPadCircle(Modifier.align(Alignment.TopCenter).offset(y = 8.dp), 4) { nativeRetro.setButton(4, it > 0); if(it > 0) hapticManager.triggerTick() }
                DPadCircle(Modifier.align(Alignment.BottomCenter).offset(y = (-8).dp), 5) { nativeRetro.setButton(5, it > 0); if(it > 0) hapticManager.triggerTick() }
                DPadCircle(Modifier.align(Alignment.CenterStart).offset(x = 8.dp), 6) { nativeRetro.setButton(6, it > 0); if(it > 0) hapticManager.triggerTick() }
                DPadCircle(Modifier.align(Alignment.CenterEnd).offset(x = (-8).dp), 7) { nativeRetro.setButton(7, it > 0); if(it > 0) hapticManager.triggerTick() }
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
                ActionCircle("A", Modifier.align(Alignment.Center).offset(x = 22.dp, y = (-22).dp), Color(0xFFEF9A9A)) { 
                    nativeRetro.setButton(8, it > 0); if (it > 0) hapticManager.triggerClick() 
                }
                ActionCircle("B", Modifier.align(Alignment.Center).offset(x = (-22).dp, y = 22.dp), Color(0xFFFFF59D)) { 
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
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .background(Color.White.copy(alpha = 0.08f), CircleShape),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun DPadCircle(modifier: Modifier, id: Int, onInput: (Int) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (isPressed) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = if (isPressed) 0.6f else 0.15f), CircleShape)
            .pointerInput(id) {
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true; onInput(1)
                    waitForUpOrCancellation()
                    isPressed = false; onInput(0)
                }
            }
    )
}

@Composable
fun ActionCircle(label: String, modifier: Modifier, accent: Color, onInput: (Int) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(55.dp)
            .clip(CircleShape)
            .background(if (isPressed) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.05f))
            .border(1.5.dp, if (isPressed) accent else Color.White.copy(alpha = 0.25f), CircleShape)
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
        Text(label, color = if (isPressed) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 18.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun CircleControlButton(label: String, onToggle: (Boolean) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            .background(if (isPressed) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f), CircleShape)
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
        Text(label, color = Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun IconPillButton(icon: ImageVector, onToggle: (Boolean) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            .background(if (isPressed) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f), CircleShape)
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
        Icon(icon, null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
    }
}
