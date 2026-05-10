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
    // STAFF: Ensuring the controller Box has zero background to act as a pure overlay
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        
        // --- LEFT CLUSTER (L + DPAD) ---
        Column(
            modifier = Modifier
                .align(if (isLandscape) Alignment.CenterStart else Alignment.BottomStart)
                // STAFF: Increased landscape padding to 64dp to pull controls OVER the 3:2 game screen
                .padding(start = if (isLandscape) 64.dp else 16.dp, bottom = if (isLandscape) 0.dp else 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircleControlButton(label = "L") { pressed ->
                nativeRetro.setInputState(0, 1, 0, 10, if (pressed) 1 else 0)
                if (pressed) hapticManager.triggerTick()
            }

            GamepadBase(modifier = Modifier.size(if (isLandscape) 140.dp else 150.dp)) {
                DPadCircle(Modifier.align(Alignment.TopCenter).offset(y = 8.dp), 4) { nativeRetro.setInputState(0, 1, 0, 4, it); if(it > 0) hapticManager.triggerTick() }
                DPadCircle(Modifier.align(Alignment.BottomCenter).offset(y = (-8).dp), 5) { nativeRetro.setInputState(0, 1, 0, 5, it); if(it > 0) hapticManager.triggerTick() }
                DPadCircle(Modifier.align(Alignment.CenterStart).offset(x = 8.dp), 6) { nativeRetro.setInputState(0, 1, 0, 6, it); if(it > 0) hapticManager.triggerTick() }
                DPadCircle(Modifier.align(Alignment.CenterEnd).offset(x = (-8).dp), 7) { nativeRetro.setInputState(0, 1, 0, 7, it); if(it > 0) hapticManager.triggerTick() }
            }
        }

        // --- RIGHT CLUSTER (R + ACTION) ---
        Column(
            modifier = Modifier
                .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomEnd)
                // STAFF: Increased landscape padding to 64dp to pull controls OVER the 3:2 game screen
                .padding(end = if (isLandscape) 64.dp else 16.dp, bottom = if (isLandscape) 0.dp else 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircleControlButton(label = "R") { pressed ->
                nativeRetro.setInputState(0, 1, 0, 11, if (pressed) 1 else 0)
                if (pressed) hapticManager.triggerTick()
            }

            GamepadBase(modifier = Modifier.size(if (isLandscape) 140.dp else 150.dp)) {
                ActionCircle("A", Modifier.align(Alignment.Center).offset(x = 22.dp, y = (-22).dp), Color(0xFFEF9A9A)) { 
                    nativeRetro.setInputState(0, 1, 0, 8, it); if (it > 0) hapticManager.triggerClick() 
                }
                ActionCircle("B", Modifier.align(Alignment.Center).offset(x = (-22).dp, y = 22.dp), Color(0xFFFFF59D)) { 
                    nativeRetro.setInputState(0, 1, 0, 0, it); if (it > 0) hapticManager.triggerClick() 
                }
            }
        }

        // --- START/SELECT ICONIC BUTTONS ---
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isLandscape) 24.dp else 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            IconPillButton(Icons.Default.HorizontalRule) { pressed ->
                nativeRetro.setInputState(0, 1, 0, 2, if (pressed) 1 else 0)
                if (pressed) hapticManager.triggerTick()
            }
            IconPillButton(Icons.Default.PlayArrow) { pressed ->
                nativeRetro.setInputState(0, 1, 0, 3, if (pressed) 1 else 0)
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
            .pointerInput(Unit) {
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
