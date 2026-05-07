package com.sharescreen.console

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharescreen.emulator.NativeRetro

@Composable
fun TouchpadController(
    nativeRetro: NativeRetro,
    hapticManager: HapticFeedbackManager,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // GBA SHOULDER BUTTONS (Top Corners)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ShoulderButton("L") { pressed ->
                nativeRetro.setInputState(0, 1, 0, 10, if (pressed) 1 else 0) // RETRO_DEVICE_ID_JOYPAD_L
                if (pressed) hapticManager.triggerTick()
            }
            ShoulderButton("R") { pressed ->
                nativeRetro.setInputState(0, 1, 0, 11, if (pressed) 1 else 0) // RETRO_DEVICE_ID_JOYPAD_R
                if (pressed) hapticManager.triggerTick()
            }
        }

        // DPAD (Left Center)
        UnifiedDPad(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 32.dp)
                .size(180.dp),
            onInput = { id, pressed ->
                nativeRetro.setInputState(0, 1, 0, id, if (pressed) 1 else 0)
                if (pressed) hapticManager.triggerTick()
            }
        )

        // Select / Start (Center Bottom)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SystemButton("SELECT") { pressed ->
                nativeRetro.setInputState(0, 1, 0, 2, if (pressed) 1 else 0)
                if (pressed) hapticManager.triggerTick()
            }
            SystemButton("START") { pressed ->
                nativeRetro.setInputState(0, 1, 0, 3, if (pressed) 1 else 0)
                if (pressed) hapticManager.triggerTick()
            }
        }

        // GBA ACTION BUTTONS (Right Center)
        // GBA only has A and B. They are usually angled.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 40.dp)
                .size(200.dp)
        ) {
            // A Button (Right-Top)
            GlassButton("A", Modifier.align(Alignment.TopEnd), Color(0xFFEF9A9A)) { 
                nativeRetro.setInputState(0, 1, 0, 8, if (it) 1 else 0)
                if (it) hapticManager.triggerClick()
            }
            // B Button (Left-Bottom)
            GlassButton("B", Modifier.align(Alignment.BottomStart), Color(0xFFFFF59D)) { 
                nativeRetro.setInputState(0, 1, 0, 0, if (it) 1 else 0)
                if (it) hapticManager.triggerClick()
            }
        }
    }
}

@Composable
fun ShoulderButton(label: String, onToggle: (Boolean) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(100.dp)
            .height(45.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(if (isPressed) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true
                    onToggle(true)
                    waitForUpOrCancellation()
                    isPressed = false
                    onToggle(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun UnifiedDPad(modifier: Modifier, onInput: (Int, Boolean) -> Unit) {
    var activeId by remember { mutableStateOf<Int?>(null) }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val position = event.changes.first().position
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val dx = position.x - center.x
                        val dy = position.y - center.y
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble())

                        val newId = if (dist < 40f) null else {
                            if (Math.abs(dx) > Math.abs(dy)) {
                                if (dx > 0) 7 else 6 // Right, Left
                            } else {
                                if (dy > 0) 5 else 4 // Down, Up
                            }
                        }

                        if (newId != activeId) {
                            activeId?.let { onInput(it, false) }
                            newId?.let { onInput(it, true) }
                            activeId = newId
                        }

                        if (event.changes.all { !it.pressed }) {
                            activeId?.let { onInput(it, false) }
                            activeId = null
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            val crossWidth = size.width * 0.35f
            val color = Color.White.copy(alpha = 0.15f)
            
            drawRoundRect(
                color = if (activeId == 4 || activeId == 5) Color.White.copy(alpha = 0.35f) else color,
                topLeft = Offset((size.width - crossWidth) / 2, 0f),
                size = Size(crossWidth, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(15f)
            )
            drawRoundRect(
                color = if (activeId == 6 || activeId == 7) Color.White.copy(alpha = 0.35f) else color,
                topLeft = Offset(0f, (size.height - crossWidth) / 2),
                size = Size(size.width, crossWidth),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(15f)
            )
        }
    }
}

@Composable
fun GlassButton(label: String, modifier: Modifier, accent: Color, onToggle: (Boolean) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(75.dp)
            .clip(CircleShape)
            .background(if (isPressed) accent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.1f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true
                    onToggle(true)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    isPressed = false
                    onToggle(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = if (isPressed) Color.White else accent.copy(alpha = 0.9f), fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun SystemButton(label: String, onToggle: (Boolean) -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(95.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (isPressed) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true
                    onToggle(true)
                    waitForUpOrCancellation()
                    isPressed = false
                    onToggle(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
