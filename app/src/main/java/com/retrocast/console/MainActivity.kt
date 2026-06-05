package com.retrocast.console

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.retrocast.emulator.NativeRetro
import com.retrocast.streaming.LibretroVideoCapturer
import com.retrocast.streaming.I420BufferPool
import com.retrocast.streaming.StreamingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.*
import java.io.File
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer

import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.Bitmap

class MainActivity : ComponentActivity(), NativeRetro.FrameCallback, NativeRetro.AudioCallback {
    companion object {
        private const val SIGNALING_PORT = 8080
        private const val SAVE_INTERVAL_MS = 30000L
        private const val MAX_PIXELS = 512 * 512
        private const val AUDIO_BUFFER_SIZE = 16384
    }

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
    private var showStats by mutableStateOf(false)
    private var streamFps by mutableStateOf(0f)
    private var streamBitrate by mutableStateOf(0)
    private var streamAudioSent by mutableStateOf(0)
    private val castFpsCounter = FpsCounter()

    private var nativePresentation: GamePresentation? = null
    private var emulatorView: EmulatorView? = null
    private var isNativeDisplayConnected by mutableStateOf(false)
    private var isCoreInitialized = false
    private var isCoreReady = false

    private var pixelBuffer: ByteBuffer? = null
    private var audioBuffer: ByteBuffer? = null
    private val i420BufferPool = I420BufferPool()

    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = object : Runnable {
        override fun run() {
            if (isCoreReady) {
                nativeRetro.saveSram()
            }
            saveHandler.postDelayed(this, SAVE_INTERVAL_MS)
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
        updateLocalDisplayState()
    }

    private fun updateLocalDisplayState() {
        nativeRetro.setLocalDisplayActive(!isCasting || nativePresentation != null)
    }

    override fun onAudioReady(buffer: ByteBuffer, samples: Int) {
        streamingManager?.sendAudio(buffer, samples)
    }

    override fun onFrameReady(pixels: ByteBuffer, width: Int, height: Int) {
        if (!isCasting) {
            try { emulatorView?.setFrame(pixels, width, height) } catch (_: Exception) {}
        }
        try { nativePresentation?.setFrame(pixels, width, height) } catch (_: Exception) {}
        if (!isCasting) return

        val fps = castFpsCounter.update()
        if (fps.toInt() != streamFps.toInt()) streamFps = fps

        val yStride = width
        val uvStride = width / 2
        val buf = i420BufferPool.acquire(width, height, yStride, uvStride)
        nativeRetro.fillI420Buffer(buf.dataY, buf.dataU, buf.dataV, width, height, yStride, uvStride)

        val frame = VideoFrame(buf, 0, System.nanoTime())
        videoCapturer.onFrameCaptured(frame)
    }

    private fun enterImmersiveMode() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.post { enterImmersiveMode() }
        hapticManager = HapticFeedbackManager(this)
        nativeRetro = NativeRetro()
        
        pixelBuffer = ByteBuffer.allocateDirect(MAX_PIXELS * 4)

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, null)
        checkExternalDisplay()
        saveHandler.post(saveRunnable)
        ThumbnailManager.init(this)
        refreshRomLibrary()
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (darkTheme) {
                darkColorScheme(primary = Color(0xFFD0BCFF), background = Color.Black, surface = Color(0xFF1C1B1F))
            } else {
                lightColorScheme(primary = Color(0xFF7A4A2A), background = Color(0xFFF5F0E8), surface = Color(0xFFEDE7DC))
            }
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            LaunchedEffect(loadedRomPath) {
                if (loadedRomPath != null) drawerState.snapTo(DrawerValue.Closed)
            }

            LaunchedEffect(showStats) {
                emulatorView?.setShowStats(showStats)
            }

            LaunchedEffect(showStats, isCasting) {
                android.util.Log.d("MainActivity", "StatsLaunchedEffect: showStats=$showStats isCasting=$isCasting sm=${streamingManager != null}")
                if (showStats && isCasting && streamingManager != null) {
                    var prevAudioSent = 0
                    while (true) {
                        try {
                            streamingManager?.pollStats()
                            streamBitrate = streamingManager?.getBitrateBps() ?: 0
                            val cur = streamingManager?.getAudioSent() ?: 0
                            streamAudioSent = (cur - prevAudioSent) / 2
                            prevAudioSent = cur
                        } catch (_: Exception) {}
                        delay(2000)
                    }
                }
            }

            MaterialTheme(colorScheme = colorScheme) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = loadedRomPath != null,
                    drawerContent = {
                        if (loadedRomPath != null) {
                            ModalDrawerSheet {
                                Spacer(modifier = Modifier.height(24.dp))
                                Text("Menu", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                                NavigationDrawerItem(icon = { Icon(Icons.Default.Save, null) }, label = { Text("Save") }, selected = false, onClick = { saveGame(); scope.launch { drawerState.close() } })
                                NavigationDrawerItem(icon = { Icon(Icons.Default.FolderOpen, null) }, label = { Text("Load") }, selected = false, onClick = { loadSramFromDisk(); scope.launch { drawerState.close() } })
                                NavigationDrawerItem(icon = { Icon(Icons.Default.Refresh, null) }, label = { Text("Reset") }, selected = false, onClick = { resetGame(); scope.launch { drawerState.close() } })
                                NavigationDrawerItem(icon = { if (showStats) Icon(Icons.Default.Check, "Stats active") }, label = { Text("Show Stats") }, selected = showStats, onClick = { showStats = !showStats })
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                NavigationDrawerItem(icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) }, label = { Text("Quit", color = MaterialTheme.colorScheme.error) }, selected = false, onClick = { shutdownGame(); scope.launch { drawerState.close() } })
                            }
                        }
                    }
                ) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (isLandscape) {
                            WideHandheldLayout(
                                nativeRetro = nativeRetro,
                                hapticManager = hapticManager,
                                isCasting = isCasting,
                                castUrl = castUrl,
                                onCastClick = { toggleCasting() },
                                onLoadRom = { romPickerLauncher.launch(arrayOf("application/octet-stream", "application/zip")) },
                                onOpenMenu = { scope.launch { drawerState.open() } },
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
                                onOpenMenu = { scope.launch { drawerState.open() } },
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
        onOpenMenu: () -> Unit,
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
                if (loadedRomPath != null) {
                    IconButton(onClick = onOpenMenu) { Icon(Icons.Default.Settings, "Menu", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    IconButton(onClick = onLoadRom) { Icon(Icons.Default.FolderOpen, "Load", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconButton(onClick = onCastClick) {
                    Icon(if (isCasting) Icons.Default.CastConnected else Icons.Default.Cast, "Cast", tint = if (isCasting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (loadedRomPath != null) {
                if (isCasting) {
                    CastDashboard(castUrl = castUrl, showStats = showStats, fps = streamFps, bitrateBps = streamBitrate, audioSent = streamAudioSent)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TouchpadController(nativeRetro = nativeRetro, hapticManager = hapticManager, isLandscape = false)
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (!isNativeDisplayConnected) {
                            AndroidView(factory = { ctx -> EmulatorView(ctx).also { emulatorView = it } }, modifier = Modifier.fillMaxWidth().aspectRatio(1.5f))
                        } else {
                            Icon(Icons.Default.Tv, "Casting", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TouchpadController(nativeRetro = nativeRetro, hapticManager = hapticManager, isLandscape = false)
                    }
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
        onOpenMenu: () -> Unit,
        romFiles: List<File>,
        onRomSelected: (File) -> Unit,
        loadedRomPath: String?
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (loadedRomPath != null) {
                if (isCasting) {
                    CastDashboard(castUrl = castUrl, showStats = showStats, fps = streamFps, bitrateBps = streamBitrate, audioSent = streamAudioSent)
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!isNativeDisplayConnected) {
                            AndroidView(factory = { ctx -> EmulatorView(ctx).also { emulatorView = it } }, modifier = Modifier.fillMaxHeight().aspectRatio(1.5f))
                        } else {
                            Icon(Icons.Default.Tv, "Casting", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(64.dp))
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
                if (loadedRomPath != null) {
                    IconButton(onClick = onOpenMenu, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                        Icon(Icons.Default.Settings, "Menu", tint = Color.White)
                    }
                } else {
                    IconButton(onClick = onLoadRom, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                        Icon(Icons.Default.FolderOpen, "Load", tint = Color.White)
                    }
                }
                IconButton(onClick = onCastClick, modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                    Icon(if (isCasting) Icons.Default.CastConnected else Icons.Default.Cast, "Cast", tint = if (isCasting) MaterialTheme.colorScheme.primary else Color.White)
                }
            }
        }
    }

    private val romPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { loadRomFromUri(it) } }

    private suspend fun initAndLoadRom(romFile: File): Boolean {
        val extension = romFile.name.substringAfterLast('.', "").lowercase()
        val coreName = when (extension) { "gba" -> "mgba_libretro_android.so"; else -> null } ?: return false
        val coreFile = File(filesDir, coreName)
        if (!coreFile.exists()) {
            isDownloading = true
            val success = CoreDownloader.downloadCore(coreName, filesDir)
            isDownloading = false
            if (!success) return false
        }
        if (!coreFile.exists()) return false

        shutdownGame()
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        val saveDir = File(filesDir, "saves").apply { mkdirs() }
        nativeRetro.setPaths(systemDir.absolutePath, saveDir.absolutePath)

        if (!isCoreInitialized) {
            nativeRetro.init(coreFile.absolutePath)
            if (pixelBuffer != null) {
                nativeRetro.setCallback(this@MainActivity, pixelBuffer)
            }
            audioBuffer = ByteBuffer.allocateDirect(AUDIO_BUFFER_SIZE)
            audioBuffer?.let { nativeRetro.setAudioCallback(this@MainActivity, it) }
            isCoreInitialized = true
        }
        if (nativeRetro.loadGame(romFile.absolutePath)) {
            loadedRomPath = romFile.absolutePath
            isCoreReady = true
            nativeRetro.start()
            return true
        }
        return false
    }

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
                if (initAndLoadRom(romFile)) {
                    refreshRomLibrary()
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
                initAndLoadRom(romFile)
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

    private fun saveGame() {
        if (isCoreReady && loadedRomPath != null) {
            nativeRetro.saveState(loadedRomPath + ".state")
        }
    }

    private fun loadSramFromDisk() {
        if (isCoreReady && loadedRomPath != null) {
            val statePath = loadedRomPath + ".state"
            if (!nativeRetro.loadState(statePath)) {
                resetGame()
            }
        }
    }

    private fun shutdownGame() {
        if (!isCoreReady) return
        nativeRetro.stop()
        nativeRetro.saveSram()
        nativeRetro.unloadGame()
        isCoreReady = false
        loadedRomPath = null
    }

    private fun resetGame() {
        if (!isCoreReady) return
        val rom = loadedRomPath ?: return
        shutdownGame()
        if (nativeRetro.loadGame(rom)) {
            loadedRomPath = rom
            isCoreReady = true
            nativeRetro.start()
        }
    }

    private fun toggleCasting() {
        if (isCasting) {
            nativeRetro.setCasting(false)
            nativeRetro.setLocalAudioMuted(false)
            signalingServer?.stopSession()
            streamingManager?.dispose()
            streamingManager = null
            isCasting = false
            castUrl = ""
            updateLocalDisplayState()
        } else {
            lifecycleScope.launch(Dispatchers.Default) {
                val ip = NetworkUtils.getLocalIpAddress(this@MainActivity)
                if (ip == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Connect to WiFi to cast", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val port = SIGNALING_PORT

                val sm = StreamingManager(this@MainActivity)
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
                sm.resetStats()
                castFpsCounter.reset()
                withContext(Dispatchers.Main) { streamFps = 0f; streamBitrate = 0; streamAudioSent = 0 }

                if (signalingServer == null) {
                    signalingServer = SignalingServer(this@MainActivity, port)
                }
                signalingServer?.startServer()
                signalingServer?.startSession(
                    clientCb = {
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
                    sdpCb = { sdpJson ->
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

                withContext(Dispatchers.Main) {
                    streamingManager = sm
                    castUrl = "http://$ip:$port"
                    isCasting = true
                    updateLocalDisplayState()
                    nativeRetro.setCasting(true)
                    nativeRetro.setLocalAudioMuted(true)
                }
            }
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
        dm.unregisterDisplayListener(displayListener)
        nativePresentation?.dismiss()
        shutdownGame()
        signalingServer?.stopServer()
        streamingManager?.dispose()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.post { enterImmersiveMode() }
        }
    }

}

@Composable
fun CastDashboard(castUrl: String, showStats: Boolean = false, fps: Float = 0f, bitrateBps: Int = 0, audioSent: Int = 0) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(castUrl, modifier = Modifier.clickable {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Cast URL", castUrl))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace)
        if (showStats) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("FPS: %.0f".format(fps), color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text("Video: %d kbps".format(bitrateBps / 1000), color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text("Audio: %d pkt/s".format(audioSent.coerceAtLeast(0)), color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun GameLibrary(romFiles: List<File>, onRomSelected: (File) -> Unit, modifier: Modifier = Modifier) {
    if (romFiles.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Gamepad, "No games", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("Tap folder to load a ROM", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
        }
    } else {
        Column(modifier = modifier) {
            Text("Games", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall,
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


