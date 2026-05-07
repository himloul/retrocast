package com.sharescreen.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.sharescreen.emulator.NativeRetro
import com.sharescreen.streaming.LibretroVideoCapturer
import com.sharescreen.streaming.StreamingManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import java.io.File
import java.nio.ByteBuffer
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), NativeRetro.VideoListener {
    private lateinit var hapticManager: HapticFeedbackManager
    private lateinit var nativeRetro: NativeRetro
    private lateinit var streamingManager: StreamingManager
    private var videoCapturer = LibretroVideoCapturer()
    
    private var signalingServer: SignalingServer? = null
    private var audioTrack: AudioTrack? = null
    
    private var isCasting by mutableStateOf(false)
    private var castUrl by mutableStateOf("")
    private var loadedRomPath by mutableStateOf<String?>(null)
    private var isDownloading by mutableStateOf(false)

    private var nativePresentation: GamePresentation? = null
    private var isNativeDisplayConnected by mutableStateOf(false)
    private var isCoreReady = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { checkExternalDisplay() }
        override fun onDisplayRemoved(displayId: Int) { checkExternalDisplay() }
        override fun onDisplayChanged(displayId: Int) { checkExternalDisplay() }
    }

    private fun checkExternalDisplay() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        
        if (displays.isNotEmpty()) {
            val display = displays[0]
            if (nativePresentation?.display?.displayId != display.displayId) {
                nativePresentation?.dismiss()
                nativePresentation = GamePresentation(this, display, nativeRetro).apply { show() }
                isNativeDisplayConnected = true
            }
        } else {
            nativePresentation?.dismiss()
            nativePresentation = null
            isNativeDisplayConnected = false
        }
    }

    private var emulationHandlerThread: HandlerThread? = null
    private var emulationChoreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null

    private fun startEmulationLoop() {
        if (emulationHandlerThread != null) return
        emulationHandlerThread = HandlerThread("EmulationThread").apply {
            start()
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
        val handler = Handler(emulationHandlerThread!!.looper)
        handler.post {
            emulationChoreographer = Choreographer.getInstance()
            frameCallback = Choreographer.FrameCallback { _ ->
                if (loadedRomPath != null && isCoreReady) {
                    nativeRetro.runFrame()
                    val samples = nativeRetro.pullAudio()
                    if (samples != null && samples.isNotEmpty()) {
                        audioTrack?.write(samples, 0, samples.size)
                    }
                    emulationChoreographer?.postFrameCallback(frameCallback)
                } else {
                    Handler(android.os.Looper.myLooper()!!).postDelayed({
                        emulationChoreographer?.postFrameCallback(frameCallback)
                    }, 100)
                }
            }
            emulationChoreographer?.postFrameCallback(frameCallback)
        }
    }

    private fun stopEmulationLoop() {
        frameCallback?.let { emulationChoreographer?.removeFrameCallback(it) }
        emulationHandlerThread?.quitSafely()
        emulationHandlerThread = null
        emulationChoreographer = null
        frameCallback = null
    }

    override fun onFrameAvailable(pixels: IntArray, width: Int, height: Int) {
        if (!isCasting) return

        // 240x160 is small enough for CPU conversion.
        val buffer = JavaI420Buffer.allocate(width, height)
        val yData = buffer.dataY
        val uData = buffer.dataU
        val vData = buffer.dataV
        val yStride = buffer.strideY
        val uStride = buffer.strideU
        val vStride = buffer.strideV

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                // 0xAABBGGRR (Little Endian memory order from native C++)
                val r = pixel and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = (pixel shr 16) and 0xFF

                val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
                yData.put(y * yStride + x, luma.toByte())

                if (x % 2 == 0 && y % 2 == 0) {
                    val u = (-0.169f * r - 0.331f * g + 0.500f * b + 128).toInt().coerceIn(0, 255)
                    val v = (0.500f * r - 0.419f * g - 0.081f * b + 128).toInt().coerceIn(0, 255)
                    uData.put((y / 2) * uStride + (x / 2), u.toByte())
                    vData.put((y / 2) * vStride + (x / 2), v.toByte())
                }
            }
        }

        // STAFF: Ensure timestamp is monotonic and non-zero
        val timestampNs = System.nanoTime()
        val frame = VideoFrame(buffer, 0, timestampNs)
        videoCapturer.onFrameCaptured(frame)
        frame.release()
    }

    private fun setupAudio() {
        val minBufferSize = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hapticManager = HapticFeedbackManager(this)
        nativeRetro = NativeRetro().apply { setVideoListener(this@MainActivity) }
        streamingManager = StreamingManager(this)
        setupAudio()
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, null)
        checkExternalDisplay()
        startEmulationLoop()
        val version = nativeRetro.getCoreVersion()
        setContent {
            val darkColorScheme = darkColorScheme(primary = Color(0xFFD0BCFF), background = Color.Black, surface = Color(0xFF1C1B1F), surfaceVariant = Color(0xFF49454F))
            MaterialTheme(colorScheme = darkColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var showCastSheet by remember { mutableStateOf(false) }
                    val sheetState = rememberModalBottomSheetState()
                    ControllerScreen(version = version, nativeRetro = nativeRetro, hapticManager = hapticManager, isCasting = isCasting, isNativeDisplayConnected = isNativeDisplayConnected, onCastClick = { showCastSheet = true }, onLoadRom = { romPickerLauncher.launch(arrayOf("application/octet-stream", "application/zip", "application/x-gba")) }, loadedRom = loadedRomPath?.substringAfterLast('/'))
                    if (showCastSheet) {
                        ModalBottomSheet(onDismissRequest = { showCastSheet = false }, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
                            CastSheetContent(isCasting = isCasting, isNativeDisplayConnected = isNativeDisplayConnected, castUrl = castUrl, onToggleCast = { toggleCasting() }, onDismiss = { showCastSheet = false })
                        }
                    }
                    if (isDownloading) {
                        Dialog(onDismissRequest = {}) {
                            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Downloading Engine...", style = MaterialTheme.typography.titleMedium)
                                    Text("Please wait, optimizing console.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val romPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { loadRomFromUri(it) } }

    private fun loadRomFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                var fileName = "game.rom"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) { fileName = cursor.getString(nameIndex) }
                }
                val inputStream = contentResolver.openInputStream(uri)
                val outputFile = File(filesDir, fileName)
                inputStream?.use { input -> outputFile.outputStream().use { output -> input.copyTo(output) } }
                var romFile = outputFile
                if (fileName.endsWith(".zip", true)) {
                    val extracted = extractRomFromZip(outputFile)
                    if (extracted != null) { romFile = extracted } else { Toast.makeText(this@MainActivity, "No supported ROM found in ZIP", Toast.LENGTH_SHORT).show(); return@launch }
                }
                val extension = romFile.name.substringAfterLast('.', "").lowercase()
                val romPath = romFile.absolutePath
                val coreName = when (extension) { "gba" -> "mgba_libretro_android.so"; else -> null }
                if (coreName == null) { Toast.makeText(this@MainActivity, "No core found for .$extension files", Toast.LENGTH_LONG).show(); return@launch }
                val coreFile = File(filesDir, coreName)
                if (!coreFile.exists()) {
                    isDownloading = true
                    val success = CoreDownloader.downloadCore(coreName, filesDir)
                    isDownloading = false
                    if (!success) { Toast.makeText(this@MainActivity, "Failed to download engine.", Toast.LENGTH_LONG).show(); return@launch }
                }
                if (nativeRetro.loadCore(coreFile.absolutePath)) {
                    if (nativeRetro.loadGame(romPath)) {
                        loadedRomPath = romPath
                        isCoreReady = true
                        Toast.makeText(this@MainActivity, "Started: ${romFile.name}", Toast.LENGTH_SHORT).show()
                    } else { Toast.makeText(this@MainActivity, "Core failed to load game", Toast.LENGTH_SHORT).show() }
                } else { Toast.makeText(this@MainActivity, "Failed to load core: ${coreFile.name}", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { isDownloading = false; Toast.makeText(this@MainActivity, "Loader Error: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun extractRomFromZip(zipFile: File): File? {
        val supported = listOf("gba")
        try {
            val zis = java.util.zip.ZipInputStream(zipFile.inputStream())
            var entry = zis.nextEntry
            while (entry != null) {
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                if (supported.contains(ext) && !entry.isDirectory) {
                    val out = File(filesDir, entry.name)
                    out.outputStream().use { zis.copyTo(it) }
                    zis.closeEntry()
                    zis.close()
                    return out
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            zis.close()
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    private fun toggleCasting() {
        if (isCasting) {
            signalingServer?.stop()
            signalingServer = null
            streamingManager.dispose()
            isCasting = false
            castUrl = ""
        } else {
            val ip = NetworkUtils.getLocalIpAddress(this)
            val port = 8080
            if (ip == null) { Toast.makeText(this, "Check WiFi", Toast.LENGTH_SHORT).show(); return }
            castUrl = "http://$ip:$port"
            streamingManager.dispose()
            streamingManager = StreamingManager(this)
            streamingManager.createPeerConnection(object : PeerConnection.Observer {
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        val json = JSONObject().apply { put("sdpMid", it.sdpMid); put("sdpMLineIndex", it.sdpMLineIndex); put("candidate", it.sdp) }
                        lifecycleScope.launch { signalingServer?.sendMessage(json.toString()) }
                    }
                }
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            })
            streamingManager.startStreaming(videoCapturer)
            signalingServer = SignalingServer(this, port).apply {
                start(
                    onClientConnected = {
                        streamingManager.createOffer { offer ->
                            offer?.let {
                                val json = JSONObject().apply { put("type", "offer"); put("sdp", it.description) }
                                lifecycleScope.launch { signalingServer?.sendMessage(json.toString()) }
                            }
                        }
                    },
                    onSdpReceived = { sdpJson ->
                        val json = JSONObject(sdpJson)
                        if (json.has("type") && json.getString("type") == "answer") {
                            val sdp = SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp"))
                            streamingManager.setRemoteDescription(sdp) { _ -> }
                        } else if (json.has("candidate")) {
                            val candidate = IceCandidate(json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate"))
                            streamingManager.addIceCandidate(candidate)
                        }
                    }
                )
            }
            isCasting = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.unregisterDisplayListener(displayListener)
        nativePresentation?.dismiss()
        stopEmulationLoop()
        audioTrack?.stop()
        audioTrack?.release()
        signalingServer?.stop()
        streamingManager.dispose()
    }
}

@Composable
fun CastSheetContent(isCasting: Boolean, isNativeDisplayConnected: Boolean, castUrl: String, onToggleCast: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Cast to Screen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        if (isNativeDisplayConnected) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CastConnected, "Native Cast", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Native HDMI/Cast Active", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
        if (!isCasting) {
            Text("Stream your game to any TV or browser on your network with ultra-low latency.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onToggleCast, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Start Web Casting") }
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Connection URL", style = MaterialTheme.typography.labelMedium)
                    Text(castUrl, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Cast URL", castUrl))
                        Toast.makeText(context, "URL Copied", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, "Copy") }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onToggleCast, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Stop Web Casting") }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ControllerScreen(version: String, nativeRetro: NativeRetro, hapticManager: HapticFeedbackManager, isCasting: Boolean, isNativeDisplayConnected: Boolean, onCastClick: () -> Unit, onLoadRom: () -> Unit, loadedRom: String?) {
    Column(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF121212), Color.Black)))) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("ShareScreen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                if (loadedRom != null) { Text(loadedRom, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                else { Text(version, style = MaterialTheme.typography.labelSmall, color = Color.DarkGray) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = onLoadRom) { Icon(Icons.Default.FolderOpen, "Load", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = onCastClick) { Icon(if (isCasting || isNativeDisplayConnected) Icons.Default.CastConnected else Icons.Default.Cast, "Cast", tint = if (isCasting || isNativeDisplayConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (!isCasting && !isNativeDisplayConnected) { AndroidView(factory = { ctx -> EmulatorView(ctx).apply { setNativeRetro(nativeRetro) } }, modifier = Modifier.fillMaxSize()) }
            TouchpadController(nativeRetro = nativeRetro, hapticManager = hapticManager, modifier = Modifier.fillMaxSize())
        }
    }
}
