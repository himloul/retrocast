package com.sharescreen.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import android.os.Handler

import android.os.Looper
import java.nio.ByteBuffer

class MainActivity : ComponentActivity(), NativeRetro.FrameCallback, NativeRetro.AudioCallback {
    private lateinit var hapticManager: HapticFeedbackManager
    private lateinit var nativeRetro: NativeRetro
    @Volatile
    private var streamingManager: StreamingManager? = null
    private var videoCapturer = LibretroVideoCapturer()
    
    private var signalingServer: SignalingServer? = null
    
    private var isCasting by mutableStateOf(false)
    private var castUrl by mutableStateOf("")
    private var loadedRomPath by mutableStateOf<String?>(null)
    private var isDownloading by mutableStateOf(false)

    private var nativePresentation: GamePresentation? = null
    private var emulatorView: EmulatorView? = null
    private var isNativeDisplayConnected by mutableStateOf(false)
    private var isCoreInitialized = false
    private var isCoreReady = false

    private var pixelBuffer: ByteBuffer? = null
    private var i420Buffer: ByteBuffer? = null
    private var audioBuffer: ByteBuffer? = null

    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = object : Runnable {
        override fun run() {
            if (isCoreReady) {
                nativeRetro.saveSram()
            }
            saveHandler.postDelayed(this, 30000)
        }
    }

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
                nativePresentation = GamePresentation(this, display).apply { show() }
                isNativeDisplayConnected = true
            }
        } else {
            nativePresentation?.dismiss()
            nativePresentation = null
            isNativeDisplayConnected = false
        }
    }

    override fun onAudioReady(buffer: ByteBuffer, samples: Int) {
        android.util.Log.d("Audio", "onAudioReady: samples=$samples, streamingManager=${streamingManager}")
        streamingManager?.sendAudio(buffer, samples)
    }

    override fun onFrameReady(pixels: ByteBuffer, width: Int, height: Int, i420: ByteBuffer, yStride: Int, uvStride: Int) {
        emulatorView?.setFrame(pixels, width, height)
        nativePresentation?.setFrame(pixels, width, height)
        if (!isCasting) return

        val i420Buffer = JavaI420Buffer.allocate(width, height)
        i420.rewind()
        val ySize = yStride * height
        val uvSize = uvStride * (height / 2)

        i420.position(0).limit(ySize)
        i420Buffer.dataY.put(i420.slice())
        i420.position(ySize).limit(ySize + uvSize)
        i420Buffer.dataU.put(i420.slice())
        i420.position(ySize + uvSize).limit(ySize + uvSize * 2)
        i420Buffer.dataV.put(i420.slice())

        val frame = VideoFrame(i420Buffer, 0, System.nanoTime())
        videoCapturer.onFrameCaptured(frame)
        frame.release()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hapticManager = HapticFeedbackManager(this)
        nativeRetro = NativeRetro()
        
        val maxPixels = 512 * 512
        pixelBuffer = ByteBuffer.allocateDirect(maxPixels * 4)
        i420Buffer = ByteBuffer.allocateDirect(maxPixels * 3 / 2)

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, null)
        checkExternalDisplay()
        saveHandler.post(saveRunnable)
        setContent {
            val darkColorScheme = darkColorScheme(primary = Color(0xFFD0BCFF), background = Color.Black, surface = Color(0xFF1C1B1F))
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            MaterialTheme(colorScheme = darkColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    var showCastSheet by remember { mutableStateOf(false) }
                    val sheetState = rememberModalBottomSheetState()
                    
                    if (isLandscape) {
                        WideHandheldLayout(
                            nativeRetro = nativeRetro,
                            hapticManager = hapticManager,
                            isCasting = isCasting,
                            onCastClick = { showCastSheet = true },
                            onLoadRom = { romPickerLauncher.launch(arrayOf("application/octet-stream", "application/zip")) }
                        )
                    } else {
                        PortraitHandheldLayout(
                            nativeRetro = nativeRetro,
                            hapticManager = hapticManager,
                            isCasting = isCasting,
                            isNativeDisplayConnected = isNativeDisplayConnected,
                            onCastClick = { showCastSheet = true },
                            onLoadRom = { romPickerLauncher.launch(arrayOf("application/octet-stream", "application/zip")) }
                        )
                    }

                    if (showCastSheet) {
                        ModalBottomSheet(onDismissRequest = { showCastSheet = false }, sheetState = sheetState, containerColor = Color(0xFF121212)) {
                            CastSheetContent(isCasting = isCasting, castUrl = castUrl, onToggleCast = { toggleCasting() })
                        }
                    }

                    if (isDownloading) {
                        Dialog(onDismissRequest = {}) {
                            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(); Spacer(modifier = Modifier.height(16.dp))
                                    Text("Downloading Engine...", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun PortraitHandheldLayout(
        nativeRetro: NativeRetro,
        hapticManager: HapticFeedbackManager,
        isCasting: Boolean,
        isNativeDisplayConnected: Boolean,
        onCastClick: () -> Unit,
        onLoadRom: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(0.1f).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onLoadRom) { Icon(Icons.Default.FolderOpen, "Load", tint = Color.LightGray) }
                Text("RetroCast", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                IconButton(onClick = onCastClick) {
                    Icon(if (isCasting) Icons.Default.CastConnected else Icons.Default.Cast, "Cast", tint = if (isCasting) MaterialTheme.colorScheme.primary else Color.LightGray)
                }
            }
            Box(modifier = Modifier.weight(0.4f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (!isCasting && !isNativeDisplayConnected) {
                    AndroidView(factory = { ctx -> EmulatorView(ctx).also { emulatorView = it } }, modifier = Modifier.fillMaxHeight().aspectRatio(1.5f))
                } else {
                    Icon(Icons.Default.Tv, "Casting", tint = Color.DarkGray, modifier = Modifier.size(48.dp))
                }
            }
            Box(modifier = Modifier.weight(0.5f).fillMaxWidth()) {
                TouchpadController(nativeRetro = nativeRetro, hapticManager = hapticManager, isLandscape = false)
            }
        }
    }

    @Composable
    fun WideHandheldLayout(
        nativeRetro: NativeRetro,
        hapticManager: HapticFeedbackManager,
        isCasting: Boolean,
        onCastClick: () -> Unit,
        onLoadRom: () -> Unit
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!isCasting && !isNativeDisplayConnected) {
                    AndroidView(factory = { ctx -> EmulatorView(ctx).also { emulatorView = it } }, modifier = Modifier.fillMaxHeight().aspectRatio(1.5f))
                } else {
                    Icon(Icons.Default.Tv, "Casting", tint = Color.DarkGray, modifier = Modifier.size(64.dp))
                }
            }
            TouchpadController(nativeRetro = nativeRetro, hapticManager = hapticManager, isLandscape = true)
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onLoadRom, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                    Icon(Icons.Default.FolderOpen, "Load", tint = Color.White)
                }
                IconButton(onClick = onCastClick, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                    Icon(if (isCasting) Icons.Default.CastConnected else Icons.Default.Cast, "Cast", tint = if (isCasting) MaterialTheme.colorScheme.primary else Color.White)
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
                    if (extracted != null) { romFile = extracted } else { return@launch }
                }
                val extension = romFile.name.substringAfterLast('.', "").lowercase()
                val coreName = when (extension) { "gba" -> "mgba_libretro_android.so"; else -> null }
                if (coreName == null) return@launch
                val coreFile = File(filesDir, coreName)
                if (!coreFile.exists()) {
                    isDownloading = true
                    val success = CoreDownloader.downloadCore(coreName, filesDir)
                    isDownloading = false
                    if (!success) return@launch
                }
                if (coreFile.exists()) {
                    if (isCoreReady) {
                        nativeRetro.stop()
                        nativeRetro.saveSram()
                        nativeRetro.unloadGame()
                        isCoreReady = false
                        loadedRomPath = null
                    }

                    val systemDir = File(filesDir, "system").apply { mkdirs() }
                    val saveDir = File(filesDir, "saves").apply { mkdirs() }
                    nativeRetro.setPaths(systemDir.absolutePath, saveDir.absolutePath)

                    if (!isCoreInitialized) {
                        nativeRetro.init(coreFile.absolutePath)
                        if (pixelBuffer != null && i420Buffer != null) {
                            nativeRetro.setCallback(this@MainActivity, pixelBuffer, i420Buffer)
                        }
                        audioBuffer = ByteBuffer.allocateDirect(16384)
                        audioBuffer?.let { nativeRetro.setAudioCallback(this@MainActivity, it) }
                        isCoreInitialized = true
                    }
                    if (nativeRetro.loadGame(romFile.absolutePath)) {
                        loadedRomPath = romFile.absolutePath
                        isCoreReady = true
                        nativeRetro.start()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to load ROM", e)
                isDownloading = false
            }
        }
    }

    private fun extractRomFromZip(zipFile: File): File? {
        val supported = listOf("gba")
        try {
            java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (supported.contains(ext) && !entry.isDirectory) {
                        val out = File(filesDir, entry.name)
                        out.outputStream().use { zis.copyTo(it) }
                        return out
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to extract ROM from ZIP", e)
        }
        return null
    }

    private fun toggleCasting() {
        if (isCasting) {
            nativeRetro.setLocalAudioMuted(false)
            signalingServer?.stop(); signalingServer = null; streamingManager?.dispose(); streamingManager = null; isCasting = false; castUrl = ""
        } else {
            val ip = NetworkUtils.getLocalIpAddress(this); val port = 8080
            if (ip == null) return
            castUrl = "http://$ip:$port"
            val sm = StreamingManager(this).also { streamingManager = it }

            sm.createPeerConnection(object : PeerConnection.Observer {
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
            sm.startStreaming(videoCapturer)
            signalingServer = SignalingServer(this, port).apply {
                start(
                    onClientConnected = {
                        sm.createOffer { offer ->
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
                            sm.setRemoteDescription(sdp) { _ -> }
                        } else if (json.has("candidate")) {
                            val candidate = IceCandidate(json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate"))
                            sm.addIceCandidate(candidate)
                        }
                    }
                )
            }
            isCasting = true
            nativeRetro.setLocalAudioMuted(true)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isCoreReady) {
            nativeRetro.saveSram()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        saveHandler.removeCallbacks(saveRunnable)
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.unregisterDisplayListener(displayListener); nativePresentation?.dismiss()
        if (isCoreReady) nativeRetro.stop()
        if (isCoreInitialized) nativeRetro.unloadGame()
        signalingServer?.stop(); streamingManager?.dispose()
    }

}

@Composable
fun CastSheetContent(isCasting: Boolean, castUrl: String, onToggleCast: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Cast to Screen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!isCasting) {
            Text("Ultra-low latency WebRTC cast.", color = Color.Gray)
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onToggleCast, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Start Web Casting") }
        } else {
            Surface(color = Color.DarkGray, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(castUrl, style = MaterialTheme.typography.titleLarge, color = Color(0xFFD0BCFF), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Cast URL", castUrl))
                    }) { Icon(Icons.Default.ContentCopy, "Copy") }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onToggleCast, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Stop Web Casting") }
        }
    }
}
