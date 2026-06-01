package com.retrocast.console

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.ktor.server.application.*
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.html.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.html.*
import java.time.Duration

class SignalingServer(private val context: Context, private val port: Int) {
    @Volatile
    private var server: ApplicationEngine? = null
    @Volatile
    private var currentSession: WebSocketServerSession? = null
    private var nsdManager: NsdManager? = null

    @Volatile
    private var onClientConnected: () -> Unit = {}
    @Volatile
    private var onSdpReceived: (String) -> Unit = {}

    private val tag = "SignalingServer"

    @Synchronized
    fun startServer() {
        if (server != null) return
        try {
            val ss = java.net.ServerSocket(port)
            ss.close()
        } catch (e: Exception) {
            Log.e(tag, "Port $port is already in use, cannot start server")
            return
        }
        server = embeddedServer(CIO, port = port) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(15)
            }

            routing {
                get("/") {
                    call.respondHtml {
                        head {
                            title("RetroCast Receiver")
                            style {
                                unsafe {
                                    +"""
                                        body { margin: 0; background: black; color: white; display: flex; justify-content: center; align-items: center; height: 100vh; overflow: hidden; font-family: sans-serif; }
                                        video { width: 100%; height: 100%; object-fit: contain; background: #050505; image-rendering: pixelated; }
                                        #status { position: absolute; top: 20px; left: 20px; color: rgba(255,255,255,0.4); font-size: 14px; font-weight: bold; background: rgba(0,0,0,0.5); padding: 8px 16px; border-radius: 20px; border: 1px solid rgba(255,255,255,0.1); }
                                    """.trimIndent()
                                }
                            }
                        }
                        body {
                            div { id = "status"; +"Tap screen to start console" }
                            video {
                                id = "remoteVideo"
                                attributes["autoplay"] = "true"
                                attributes["playsinline"] = "true"
                                attributes["muted"] = "true"
                            }
                            script {
                                unsafe {
                                    +"""
                                         const status = document.getElementById('status');
                                         const video = document.getElementById('remoteVideo');
                                         let ws;
                                         let audioCtx = null;
                                         let audioScheduledTime = 0;
                                         let targetSampleRate = 44100;
                                         let audioQueue = [];
                                         let audioPlaying = false;

                                         function scheduleAudioQueue() {
                                             audioPlaying = true;
                                             const now = audioCtx.currentTime;
                                             if (audioScheduledTime < now + 0.05 || audioScheduledTime - now > 0.5) {
                                                 audioScheduledTime = now + 0.1;
                                             }
                                             while (audioQueue.length > 0) {
                                                 const buf = audioQueue.shift();
                                                 const src = audioCtx.createBufferSource();
                                                 src.buffer = buf;
                                                 src.connect(audioCtx.destination);
                                                 src.start(audioScheduledTime);
                                                 audioScheduledTime += buf.duration;
                                             }
                                             audioPlaying = false;
                                             if (audioQueue.length > 0) scheduleAudioQueue();
                                         }

                                         function connect() {
                                             ws = new WebSocket('ws://' + location.host + '/ws');
                                             
                                             const pc = new RTCPeerConnection({
                                                 iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
                                             });

                                             pc.ondatachannel = (event) => {
                                                 const dc = event.channel;
                                                 if (dc.label === 'audio') {
                                                     dc.binaryType = 'arraybuffer';
                                                     dc.onmessage = (e) => {
                                                         try {
                                                             if (!audioCtx) return;
                                                             if (audioCtx.state === 'suspended') audioCtx.resume();
                                                             const raw = e.data;
                                                             const pcm = new Int16Array(raw);
                                                             if (pcm.length < 4) return;
                                                             const frames = pcm.length / 2;
                                                             const buffer = audioCtx.createBuffer(2, frames, targetSampleRate);
                                                             const left = buffer.getChannelData(0);
                                                             const right = buffer.getChannelData(1);
                                                             for (let i = 0; i < frames; i++) {
                                                                 left[i] = pcm[i * 2] / 32768;
                                                                 right[i] = pcm[i * 2 + 1] / 32768;
                                                             }
                                                             audioQueue.push(buffer);
                                                             if (audioQueue.length >= 3 && !audioPlaying) scheduleAudioQueue();
                                                         } catch (err) {
                                                             console.error('Audio error:', err);
                                                         }
                                                     };
                                                 }
                                             };

                                             pc.ontrack = (event) => {
                                                 console.log('Track received:', event.track.kind);
                                                 status.innerText = 'Streaming Active';
                                                 if (video.srcObject !== event.streams[0]) {
                                                     video.srcObject = event.streams[0];
                                                 }
                                             };

                                             pc.onconnectionstatechange = () => {
                                                 console.log('State:', pc.connectionState);
                                                 if (pc.connectionState === 'connected') status.innerText = 'Connected';
                                             };

                                             ws.onmessage = async (event) => {
                                                 const msg = JSON.parse(event.data);
                                                 if (msg.type === 'offer') {
                                                     if (msg.sampleRate) targetSampleRate = msg.sampleRate;
                                                     await pc.setRemoteDescription(new RTCSessionDescription(msg));
                                                     const answer = await pc.createAnswer();
                                                     await pc.setLocalDescription(answer);
                                                     ws.send(JSON.stringify(pc.localDescription));
                                                 } else if (msg.candidate) {
                                                     await pc.addIceCandidate(new RTCIceCandidate(msg));
                                                 }
                                             };

                                             pc.onicecandidate = (event) => {
                                                 if (event.candidate) ws.send(JSON.stringify(event.candidate));
                                             };
                                             
                                             ws.onclose = () => setTimeout(connect, 2000);
                                         }

                                         window.onclick = () => {
                                             video.play().catch(e => console.log('Play error:', e));
                                             status.innerText = 'Connecting...';
                                             if (!ws) connect();
                                             if (!audioCtx) {
                                                 audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: targetSampleRate });
                                                 audioScheduledTime = audioCtx.currentTime + 0.1;
                                             }
                                             if (audioCtx.state === 'suspended') audioCtx.resume();
                                         };
                                    """.trimIndent()
                                }
                            }
                        }
                    }
                }

                webSocket("/ws") {
                    currentSession = this
                    onClientConnected()
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                onSdpReceived(text)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "WebSocket error: ${e.message}")
                    } finally {
                        currentSession = null
                    }
                }
            }
        }
        
        try {
            server?.start(wait = false)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server on port $port", e)
            server = null
            return
        }
        try { registerService(port) } catch (e: Exception) {
            Log.e(tag, "mDNS registration failed", e)
        }
    }

    fun stopServer() {
        try { runBlocking { currentSession?.close() } } catch (e: Exception) {
            Log.e(tag, "Error closing session", e)
        }
        try { server?.stop(500, 1000) } catch (e: Exception) {
            Log.e(tag, "Error stopping server", e)
        }
        server = null
        unregisterService()
    }

    fun startSession(clientCb: () -> Unit, sdpCb: (String) -> Unit) {
        onClientConnected = clientCb
        onSdpReceived = sdpCb
    }

    fun stopSession() {
        try { runBlocking { currentSession?.close() } } catch (e: Exception) {
            Log.e(tag, "Error closing session", e)
        }
    }

    private val nsdListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(p0: NsdServiceInfo?) {}
        override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
        override fun onServiceUnregistered(p0: NsdServiceInfo?) {}
        override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
    }

    private fun registerService(servicePort: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "RetroCast-Console"
            serviceType = "_http._tcp."
            port = servicePort
        }
        nsdManager = (context.getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
            registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListener)
        }
    }

    private fun unregisterService() {
        try {
            nsdManager?.unregisterService(nsdListener)
        } catch (e: Exception) {
            Log.e(tag, "Error unregistering mDNS", e)
        }
        nsdManager = null
    }

    suspend fun sendMessage(message: String) {
        try {
            currentSession?.send(Frame.Text(message))
        } catch (e: Exception) {
            Log.e(tag, "Error sending message: ${e.message}")
        }
    }
}
