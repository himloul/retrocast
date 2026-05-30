package com.sharescreen.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
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
import com.sharescreen.streaming.I420BufferPool
import com.sharescreen.streaming.StreamingManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import java.io.File
import android.os.Handler

import android.os.Looper
import java.nio.ByteBuffer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

import android.graphics.Bitmap

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
    private var romFiles by mutableStateOf<List<File>>(emptyList())

    private var nativePresentation: GamePresentation? = null
    private var emulatorView: EmulatorView? = null
    private var isNativeDisplayConnected by mutableStateOf(false)
    private var isCoreInitialized = false
    private var isCoreReady = false

    private var pixelBuffer: ByteBuffer? = null
    private var i420Buffer: ByteBuffer? = null
    private var audioBuffer: ByteBuffer? = null
    private val i420BufferPool = I420BufferPool()

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
        streamingManager?.sendAudio(buffer, samples)
    }

    override fun onFrameReady(pixels: ByteBuffer, width: Int, height: Int, i420: ByteBuffer, yStride: Int, uvStride: Int) {
        emulatorView?.setFrame(pixels, width, height)
        nativePresentation?.setFrame(pixels, width, height)
        if (!isCasting) return

        val buf = i420BufferPool.acquire(width, height, yStride, uvStride)
        val ySize = yStride * height
        val uvSize = uvStride * (height / 2)

        i420.position(0).limit(ySize)
        buf.dataY.put(i420.slice())
        i420.position(ySize).limit(ySize + uvSize)
        buf.dataU.put(i420.slice())
        i420.position(ySize + uvSize).limit(ySize + uvSize * 2)
        buf.dataV.put(i420.slice())

        val frame = VideoFrame(buf, 0, System.nanoTime())
        videoCapturer.onFrameCaptured(frame)
        frame.release()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.post {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.apply {
                    hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
            }
        }
        hapticManager = HapticFeedbackManager(this)
        nativeRetro = NativeRetro()
        
        val maxPixels = 512 * 512
        pixelBuffer = ByteBuffer.allocateDirect(maxPixels * 4)
        i420Buffer = ByteBuffer.allocateDirect(maxPixels * 3 / 2)

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, null)
        checkExternalDisplay()
        saveHandler.post(saveRunnable)
        ThumbnailManager.init(this)
        refreshRomLibrary()
        setContent {
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(this)
            } else {
                darkColorScheme(primary = Color(0xFFD0BCFF), background = Color.Black, surface = Color(0xFF1C1B1F))
            }
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    if (isLandscape) {
                        WideHandheldLayout(
                            nativeRetro = nativeRetro,
                            hapticManager = hapticManager,
                            isCasting = isCasting,
                            castUrl = castUrl,
                            onCastClick = { toggleCasting() },
                            onLoadRom = { romPickerLauncher.launch(arrayOf("application/octet-stream", "application/zip")) },
                            romFiles = romFiles,
                            onRomSelected = { file -> loadLocalRom(file) },
                            loadedRomPath = loadedRomPath
                        )
                    } else {
                        PortraitHandheldLayout(
                            nativeRetro = nativeRetro,
                            hapticManager = hapticManager,
                            isCasting = isCasting,
                            castUrl = castUrl,
                            isNativeDisplayConnected = isNativeDisplayConnected,
                            onCastClick = { toggleCasting() },
                            onLoadRom = { romPickerLauncher.launch(arrayOf("application/octet-stream", "application/zip")) },
                            romFiles = romFiles,
                            onRomSelected = { file -> loadLocalRom(file) },
                            loadedRomPath = loadedRomPath
                        )
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
        castUrl: String,
        isNativeDisplayConnected: Boolean,
        onCastClick: () -> Unit,
        onLoadRom: () -> Unit,
        romFiles: List<File>,
        onRomSelected: (File) -> Unit,
        loadedRomPath: String?
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onLoadRom) { Icon(Icons.Default.FolderOpen, "Load", tint = Color.LightGray) }
                Text("RetroCast", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                IconButton(onClick = onCastClick) {
                    Icon(if (isCasting) Icons.Default.CastConnected else Icons.Default.Cast, "Cast", tint = if (isCasting) MaterialTheme.colorScheme.primary else Color.LightGray)
                }
            }
            if (loadedRomPath != null) {
                if (isCasting) {
                    Box(modifier = Modifier.weight(0.45f).fillMaxWidth()) {
                        CastDashboard(castUrl = castUrl)
                    }
                } else {
                    Box(modifier = Modifier.weight(0.45f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (!isNativeDisplayConnected) {
                            AndroidView(factory = { ctx -> EmulatorView(ctx).also { emulatorView = it } }, modifier = Modifier.fillMaxHeight().aspectRatio(1.5f))
                        } else {
                            Icon(Icons.Default.Tv, "Casting", tint = Color.DarkGray, modifier = Modifier.size(48.dp))
                        }
                    }
                }
                Box(modifier = Modifier.weight(0.5f).fillMaxWidth()) {
                    TouchpadController(nativeRetro = nativeRetro, hapticManager = hapticManager, isLandscape = false)
                }
            } else {
                GameLibrary(romFiles = romFiles, onRomSelected = onRomSelected, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    @Composable
    fun WideHandheldLayout(
        nativeRetro: NativeRetro,
        hapticManager: HapticFeedbackManager,
        isCasting: Boolean,
        castUrl: String,
        onCastClick: () -> Unit,
        onLoadRom: () -> Unit,
        romFiles: List<File>,
        onRomSelected: (File) -> Unit,
        loadedRomPath: String?
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (loadedRomPath != null) {
                if (isCasting) {
                    CastDashboard(castUrl = castUrl)
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!isNativeDisplayConnected) {
                            AndroidView(factory = { ctx -> EmulatorView(ctx).also { emulatorView = it } }, modifier = Modifier.fillMaxHeight().aspectRatio(1.5f))
                        } else {
                            Icon(Icons.Default.Tv, "Casting", tint = Color.DarkGray, modifier = Modifier.size(64.dp))
                        }
                    }
                }
                TouchpadController(nativeRetro = nativeRetro, hapticManager = hapticManager, isLandscape = true)
            } else {
                GameLibrary(romFiles = romFiles, onRomSelected = onRomSelected, modifier = Modifier.fillMaxSize())
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopCenter),
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
                        refreshRomLibrary()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to load ROM", e)
                isDownloading = false
            }
        }
    }

    private fun refreshRomLibrary() {
        romFiles = filesDir.listFiles { f -> f.extension.equals("gba", ignoreCase = true) }?.sortedBy { it.name }?.toList() ?: emptyList()
    }

    private fun loadLocalRom(romFile: File) {
        lifecycleScope.launch {
            try {
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
                android.util.Log.e("MainActivity", "Failed to load local ROM", e)
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
            if (ip == null) { Toast.makeText(this, "Connect to WiFi to cast", Toast.LENGTH_SHORT).show(); return }
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
                                val json = JSONObject().apply {
                                    put("type", "offer")
                                    put("sdp", it.description)
                                    put("sampleRate", nativeRetro.getSampleRate())
                                }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.post {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                } else {
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    )
                }
            }
        }
    }

}

@Composable
fun CastDashboard(castUrl: String) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(castUrl, style = MaterialTheme.typography.titleMedium, color = Color(0xFFD0BCFF), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            IconButton(onClick = {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Cast URL", castUrl))
            }) { Icon(Icons.Default.ContentCopy, "Copy", tint = Color.Gray, modifier = Modifier.size(18.dp)) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        QrCodeView(url = castUrl, modifier = Modifier.size(150.dp))
    }
}

@Composable
fun GameLibrary(romFiles: List<File>, onRomSelected: (File) -> Unit, modifier: Modifier = Modifier) {
    if (romFiles.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Gamepad, "No games", tint = Color.DarkGray, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("Tap folder to load a ROM", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
            }
        }
    } else {
        Column(modifier = modifier) {
            Text("Games", color = Color.Gray, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(romFiles) { rom ->
                    GameCard(rom = rom, onClick = { onRomSelected(rom) })
                }
            }
        }
    }
}

@Composable
fun GameCard(rom: File, onClick: () -> Unit) {
    val name = rom.nameWithoutExtension
    val initials = name.split(Regex("[^A-Za-z0-9]"))
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "GB" }

    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(rom) {
        thumbnail = ThumbnailManager.load(name)
    }

    Card(
        onClick = onClick,
        modifier = Modifier.size(120.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun QrCodeView(url: String, modifier: Modifier = Modifier) {
    val matrix = remember(url) {
        try {
            QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 256, 256)
        } catch (e: Exception) {
            null
        }
    }
    if (matrix == null) return
    Canvas(modifier = modifier) {
        val cellSize = minOf(size.width, size.height) / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(x * cellSize, y * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}
