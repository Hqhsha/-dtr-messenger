package com.dtr.nativeapp

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import io.socket.client.IO
import io.socket.client.Socket
import java.util.concurrent.TimeUnit

private const val BASE_URL = "https://dtr-messenger-z1ee.onrender.com"
private val Bg = Color(0xFF0D0D12)
private val Panel = Color(0xFF171720)
private val Accent = Color(0xFF9270FF)
private val Muted = Color(0xFF9B9AA8)
private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

data class DtrUser(val id: String, val username: String, val displayName: String)
data class DtrChat(val id: String, val title: String, val subtitle: String, val type: String, val peerId: String? = null)
data class DtrReaction(val emoji: String, val count: Int, val mine: Boolean)
data class DtrMessage(val id: String, val senderId: String, val body: String, val displayName: String, val createdAt: String, val reactions: List<DtrReaction> = emptyList(), val editedAt: String? = null, val messageType: String = "text", val mediaUrl: String? = null)
data class DtrCall(val peerId: String, val chatId: String, val peerName: String, val video: Boolean, val incoming: Boolean, val status: String, val offer: JSONObject? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("dtr-native", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, background = Bg, surface = Panel,
                onBackground = Color.White, onSurface = Color.White
            )) {
                DtrApp(
                    savedToken = prefs.getString("token", null),
                    saveToken = { token -> prefs.edit().putString("token", token).apply() },
                    clearToken = { prefs.edit().remove("token").apply() }
                )
            }
        }
    }
}

@Composable
private fun DtrApp(savedToken: String?, saveToken: (String) -> Unit, clearToken: () -> Unit) {
    var token by remember { mutableStateOf(savedToken) }
    var page by remember { mutableStateOf("chats") }
    var me by remember { mutableStateOf<DtrUser?>(null) }
    var chats by remember { mutableStateOf<List<DtrChat>>(emptyList()) }
    var active by remember { mutableStateOf<DtrChat?>(null) }
    var messages by remember { mutableStateOf<List<DtrMessage>>(emptyList()) }
    var selectedMessage by remember { mutableStateOf<DtrMessage?>(null) }
    var editingMessage by remember { mutableStateOf<DtrMessage?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var pickerMime by remember { mutableStateOf("image/*") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var socket by remember { mutableStateOf<Socket?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var call by remember { mutableStateOf<DtrCall?>(null) }
    var callEngine by remember { mutableStateOf<DtrCallEngine?>(null) }
    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) pendingCallAction?.invoke() else error = "Для звонка нужны разрешения на микрофон и камеру."
        pendingCallAction = null
    }
    fun withCallPermissions(video: Boolean, action: () -> Unit) {
        val permissions = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) action()
        else { pendingCallAction = action; permissionLauncher.launch(permissions) }
    }
    fun finishCall(notifyPeer: Boolean = true) {
        val current = call
        if (notifyPeer && current != null) socket?.emit("call:end", JSONObject().put("to", current.peerId).put("chatId", current.chatId))
        callEngine?.dispose(); callEngine = null; call = null
    }
    fun beginCall(video: Boolean) {
        val chat = active ?: return
        val peerId = chat.peerId ?: return
        val live = socket ?: run { error = "Нет соединения с сервером."; return }
        withCallPermissions(video) {
            try {
                val engine = DtrCallEngine(context, live, peerId, chat.id, video) { status ->
                    scope.launch { call = call?.copy(status = status) }
                }
                callEngine = engine
                call = DtrCall(peerId, chat.id, chat.title, video, false, "calling")
                engine.startOutgoing()
            } catch (e: Exception) { finishCall(false); error = e.message ?: "Не удалось начать звонок." }
        }
    }
    fun acceptCall() {
        val current = call ?: return
        val offer = current.offer ?: return
        withCallPermissions(current.video) {
            try {
                val engine = DtrCallEngine(context, socket ?: throw IllegalStateException("Нет соединения"), current.peerId, current.chatId, current.video) { status ->
                    scope.launch { call = call?.copy(status = status) }
                }
                callEngine = engine
                engine.acceptIncoming(offer)
                call = current.copy(incoming = false, status = "connecting", offer = null)
            } catch (e: Exception) { finishCall(); error = e.message ?: "Не удалось принять звонок." }
        }
    }

    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, auth: String? = token): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(BASE_URL + path)
            if (auth != null) builder.header("Authorization", "Bearer $auth")
            if (body != null) builder.method(method, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            else if (method != "GET") builder.method(method, ByteArray(0).toRequestBody(null))
            val response = http.newCall(builder.build()).execute()
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(
                runCatching { JSONObject(raw).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Ошибка сервера: ${response.code}"
            )
            JSONObject(raw.ifBlank { "{}" })
        }

    suspend fun uploadMedia(uri: Uri, mimeType: String) {
        val chat = active ?: return
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                val mime = context.contentResolver.getType(uri) ?: mimeType
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: if (mime.startsWith("video/")) "video.mp4" else "photo.jpg"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Не удалось прочитать файл")
                if (bytes.size > 12 * 1024 * 1024) throw IllegalStateException("Файл больше 12 МБ")
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("media", name, bytes.toRequestBody(mime.toMediaType())).build()
                val req = Request.Builder().url(BASE_URL + "/api/chats/${chat.id}/media").header("Authorization", "Bearer ${token ?: ""}").post(multipart).build()
                http.newCall(req).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException(runCatching { JSONObject(raw).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Ошибка отправки: ${response.code}")
                    JSONObject(raw)
                }
            }
            val m = result.optJSONObject("message")
            if (m != null) {
                val item = DtrMessage(m.optString("id"), m.optString("senderId"), m.optString("body"), m.optString("displayName"), m.optString("createdAt"), messageType = m.optString("messageType", if (mimeType.startsWith("video/")) "video" else "image"), mediaUrl = m.optString("mediaUrl").takeIf { it.isNotBlank() && it != "null" })
                if (messages.none { it.id == item.id }) messages = messages + item
                loadChats()
            }
        } catch (e: Exception) { error = e.message ?: "Не удалось отправить файл" }
        finally { loading = false }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { uploadMedia(uri, pickerMime) }
    }

    suspend fun loadChats() {
        val data = request("/api/chats")
        val array = data.optJSONArray("chats") ?: JSONArray()
        chats = (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            val user = c.optJSONObject("user")
            DtrChat(
                c.optString("chatId"),
                c.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: user?.optString("displayName", "Чат").orEmpty().ifBlank { "Чат" },
                c.optString("lastMessage").takeIf { it.isNotBlank() && it != "null" } ?: "Нет сообщений",
                c.optString("type", "direct"),
                user?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    suspend fun loadMessages(chat: DtrChat) {
        val data = request("/api/chats/${chat.id}/messages")
        val array = data.optJSONArray("messages") ?: JSONArray()
        messages = (0 until array.length()).map { i ->
            val m = array.getJSONObject(i)
            val reactions = m.optJSONArray("reactions")?.let { a -> (0 until a.length()).mapNotNull { k -> a.optJSONObject(k)?.let { DtrReaction(it.optString("emoji"),it.optInt("count"),it.optBoolean("mine")) } } } ?: emptyList()
            DtrMessage(m.optString("id"), m.optString("senderId"), m.optString("body"), m.optString("displayName"), m.optString("createdAt"), reactions, m.optString("editedAt").takeIf { it.isNotBlank() && it!="null" }, m.optString("messageType","text"), m.optString("mediaUrl").takeIf { it.isNotBlank() && it!="null" })
        }
    }

    LaunchedEffect(token) {
        if (token != null) {
            loading = true
            try {
                val profile = request("/api/me")
                val u = profile.optJSONObject("user") ?: JSONObject()
                me = DtrUser(u.optString("id"), u.optString("username"), u.optString("displayName"))
                loadChats()
                val opts = IO.Options.builder().setAuth(mapOf("token" to token)).setReconnection(true).build()
                val live = IO.socket(BASE_URL, opts)
                live.on("call:incoming") { args ->
                    val data = args.firstOrNull() as? JSONObject ?: return@on
                    scope.launch {
                        try {
                            val from = data.optString("from")
                            val profileData = request("/api/users/$from/profile")
                            val u = profileData.optJSONObject("user") ?: JSONObject()
                            val offer = data.optJSONObject("offer")
                            call = DtrCall(from, data.optString("chatId"), u.optString("displayName", "Контакт"), data.optBoolean("video"), true, "incoming", offer)
                        } catch (_: Exception) { live.emit("call:end", JSONObject().put("to", data.optString("from")).put("chatId", data.optString("chatId"))) }
                    }
                }
                live.on("call:answered") { args -> val data = args.firstOrNull() as? JSONObject ?: return@on; scope.launch { callEngine?.onAnswer(data.optJSONObject("answer") ?: JSONObject()) } }
                live.on("call:ice-candidate") { args -> val data = args.firstOrNull() as? JSONObject ?: return@on; scope.launch { callEngine?.onIce(data) } }
                live.on("call:ended") { scope.launch { finishCall(false) } }
                live.on("chat:message") { args ->
                    val obj = args.firstOrNull() as? JSONObject ?: return@on
                    val msg = DtrMessage(obj.optString("id"), obj.optString("senderId"), obj.optString("body"), obj.optString("displayName"), obj.optString("createdAt"))
                    if (active?.id == obj.optString("chatId")) {
                        messages = if (messages.any { it.id == msg.id }) messages else messages + msg
                    }
                    scope.launch { runCatching { loadChats() } }
                }
                live.connect()
                socket = live
            } catch (e: Exception) {
                error = e.message ?: "Не удалось подключиться"
                if (error?.contains("Сессия истекла") == true || error?.contains("401") == true) {
                    token = null; clearToken(); me = null
                }
            } finally { loading = false }
        } else {
            socket?.disconnect(); socket = null; me = null; chats = emptyList(); active = null; messages = emptyList()
        }
    }

    LaunchedEffect(active?.id) {
        val selected = active ?: return@LaunchedEffect
        try { loadMessages(selected) } catch (e: Exception) { error = e.message }
    }

    Surface(modifier = Modifier.fillMaxSize().imePadding(), color = Bg) {
        if (token == null) {
            AuthScreen(
                username, { username = it }, password, { password = it },
                displayName, { displayName = it }, register, { register = it },
                loading, error,
                onSubmit = {
                    scope.launch {
                        loading = true; error = null
                        try {
                            val payload = JSONObject().put("username", username.trim()).put("password", password)
                            if (register) payload.put("displayName", displayName.trim())
                            val data = request("/api/auth/" + if (register) "register" else "login", "POST", payload, null)
                            val newToken = data.getString("token")
                            saveToken(newToken); token = newToken; password = ""
                        } catch (e: Exception) { error = e.message ?: "Не удалось войти" }
                        finally { loading = false }
                    }
                }
            )
        } else if (active == null && page != "chats") {
            NativeFeaturePage(page, token ?: "", api = { path, method, body -> request(path, method, body) }, onBack = { page = "chats" }, onNavigate = { page = it })
        } else if (active == null) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        DtrLogo(46)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("DTR", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                            Text("ТВОЁ ОБЩЕНИЕ. БЕЗ ГРАНИЦ.", color = Muted, fontSize = 9.sp, letterSpacing = 1.1.sp)
                        }
                    }
                    TextButton(onClick = { token = null; clearToken() }) { Text("Выйти", color = Muted) }
                }
                Text("Сообщения", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(14.dp))
                if (loading && chats.isEmpty()) CircularProgressIndicator(color = Accent)
                if (chats.isEmpty() && !loading) Text("Пока нет чатов", color = Muted, modifier = Modifier.padding(top = 24.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(chats, key = { it.id }) { chat ->
                        Row(
                            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp))
                                .clickable { active = chat; page = "chats" }.padding(15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(48.dp).background(Accent.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                                Text(chat.title.take(1).uppercase(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(chat.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Spacer(Modifier.height(3.dp))
                                Text(chat.subtitle, color = Muted, fontSize = 13.sp, maxLines = 1)
                            }
                            Text("›", color = Muted, fontSize = 25.sp)
                        }
                    }
                }
                error?.let { Text(it, color = Color(0xFFFF7777), modifier = Modifier.padding(top = 8.dp)) }
                NativeBottomNav(page = page, onSelect = { page = it })
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { active = null; page = "chats" }) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(active!!.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("DTR • чат", color = Muted, fontSize = 11.sp)
                    }
                    if (active!!.type == "direct" && active!!.peerId != null) {
                        TextButton(onClick = { beginCall(false) }) { Text("☎", color = Accent, fontSize = 20.sp) }
                        TextButton(onClick = { beginCall(true) }) { Text("▣", color = Accent, fontSize = 20.sp) }
                    }
                    TextButton(onClick = { scope.launch { runCatching { loadMessages(active!!) } } }) { Text("↻", color = Accent, fontSize = 20.sp) }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages, key = { it.id }) { msg ->
                        val mine = msg.senderId == me?.id
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                            Column(
                                Modifier.widthIn(max = 310.dp).background(if (selectedMessage?.id==msg.id) Accent.copy(alpha=.22f) else if (mine) Color(0xFF40336A) else Panel, RoundedCornerShape(17.dp)).clickable { selectedMessage=if(selectedMessage?.id==msg.id)null else msg }.padding(horizontal = 13.dp, vertical = 9.dp)
                            ) {
                                if (!mine) Text(msg.displayName.ifBlank { "Участник" }, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(when(msg.messageType) { "image" -> "▧  Фото\\n${msg.body}"; "video" -> "▶  Видео\\n${msg.body}"; "audio" -> "♫  Голосовое сообщение\\n${msg.body}"; else -> msg.body }, color = Color.White, fontSize = 15.sp)
                                if(msg.editedAt!=null) Text("изменено",color=Muted,fontSize=10.sp)
                                if(msg.reactions.isNotEmpty()) Text(msg.reactions.joinToString("  ") { it.emoji+" "+it.count },color=Accent,fontSize=12.sp,modifier=Modifier.padding(top=4.dp))
                            }
                        }
                    }
                }
                selectedMessage?.let { selected ->
                    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal=6.dp,vertical=2.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                        listOf("👍","❤️","😂","🔥").forEach { emoji -> TextButton(onClick={scope.launch{try{val d=request("/api/messages/"+selected.id+"/reactions","POST",JSONObject().put("emoji",emoji));val a=d.optJSONArray("reactions")?:JSONArray();val updated=(0 until a.length()).mapNotNull{k->a.optJSONObject(k)?.let{DtrReaction(it.optString("emoji"),it.optInt("count"),it.optBoolean("mine"))}};messages=messages.map{if(it.id==selected.id)it.copy(reactions=updated)else it};selectedMessage=null}catch(e:Exception){error=e.message}}}){Text(emoji)} }
                        if(selected.senderId==me?.id) {
                            TextButton(onClick={editingMessage=selected;draft=selected.body;selectedMessage=null}){Text("Изменить",color=Accent)}
                            TextButton(onClick={scope.launch{try{request("/api/messages/"+selected.id,"DELETE",null);messages=messages.map{if(it.id==selected.id)it.copy(body="Сообщение удалено",editedAt=null,reactions=emptyList())else it};selectedMessage=null}catch(e:Exception){error=e.message}}}){Text("Удалить",color=Color(0xFFFF7777))}
                        }
                        TextButton(onClick={selectedMessage=null}){Text("×",color=Muted)}
                    }
                }
                Row(Modifier.fillMaxWidth().background(Panel).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (editingMessage == null) {
                        IconButton(onClick = { pickerMime = "image/*"; mediaPicker.launch(pickerMime) }, enabled = !loading) { Text("＋", color = Accent, fontSize = 24.sp) }
                        IconButton(onClick = { pickerMime = "video/*"; mediaPicker.launch(pickerMime) }, enabled = !loading) { Text("▣", color = Accent, fontSize = 19.sp) }
                    }
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text(if(editingMessage!=null) "Редактирование…" else "Сообщение…", color = Muted) }, shape = RoundedCornerShape(24.dp),
                        maxLines = 4, colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Accent, unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Bg, unfocusedContainerColor = Bg
                        )
                    )
                    Spacer(Modifier.width(7.dp))
                    IconButton(
                        enabled = draft.isNotBlank(),
                        onClick = {
                            val text = draft.trim()
                            val chat = active ?: return@IconButton
                            draft = ""
                            scope.launch {
                                try {
                                    if(editingMessage!=null) { val target=editingMessage!!; val data=request("/api/messages/"+target.id,"PATCH",JSONObject().put("body",text)); val m=data.optJSONObject("message")?:return@launch; messages=messages.map{if(it.id==target.id)it.copy(body=m.optString("body"),editedAt=m.optString("editedAt"))else it}; editingMessage=null } else { val data=request("/api/chats/"+chat.id+"/messages","POST",JSONObject().put("body",text)); val m=data.optJSONObject("message")?:return@launch; val item=DtrMessage(m.optString("id"),m.optString("senderId"),m.optString("body"),m.optString("displayName"),m.optString("createdAt")); if(messages.none{it.id==item.id})messages=messages+item }; loadChats()
                                } catch (e: Exception) { draft = text; error = e.message }
                            }
                        }
                    ) { Icon(Icons.Default.Send, contentDescription = "Отправить", tint = if (draft.isBlank()) Muted else Accent) }
                }
                error?.let { Text(it, color = Color(0xFFFF7777), modifier = Modifier.padding(8.dp)) }
            }
        }
        if (call != null) CallOverlay(call!!, callEngine, onAccept = { acceptCall() }, onEnd = { finishCall() })
    }
}

@Composable
private fun CallOverlay(call: DtrCall, engine: DtrCallEngine?, onAccept: () -> Unit, onEnd: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color(0xF20A0910)) {
        Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (call.video) "Видеозвонок" else "Голосовой звонок", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(when(call.status) { "incoming" -> "Входящий вызов"; "calling" -> "Вызов…"; "active" -> "На связи"; "failed" -> "Не удалось соединиться"; else -> "Соединение…" }, color = Muted)
            Spacer(Modifier.height(20.dp))
            if (call.video && engine != null) {
                if (engine.remoteVideoTrack != null) CallVideo(engine.remoteVideoTrack, engine.eglContext, Modifier.fillMaxWidth().weight(1f), false)
                else Box(Modifier.fillMaxWidth().weight(1f).background(Panel, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) { Text(call.peerName, color = Color.White, fontSize = 24.sp) }
                if (engine.localVideoTrack != null) CallVideo(engine.localVideoTrack, engine.eglContext, Modifier.width(112.dp).height(156.dp), true)
            } else {
                Box(Modifier.size(132.dp).background(Accent.copy(alpha = .2f), CircleShape), contentAlignment = Alignment.Center) { Text(call.peerName.take(1).uppercase(), color = Accent, fontSize = 48.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(18.dp)); Text(call.peerName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (call.incoming) Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF248A55))) { Text("Принять") }
                else {
                    Button(onClick = { engine?.toggleMic(!(engine?.micEnabled ?: true)) }, colors = ButtonDefaults.buttonColors(containerColor = Panel)) { Text(if (engine?.micEnabled == false) "Вкл. микрофон" else "Микрофон") }
                    if (call.video) {
                        Button(onClick = { engine?.toggleCamera(!(engine?.cameraEnabled ?: true)) }, colors = ButtonDefaults.buttonColors(containerColor = Panel)) { Text("Камера") }
                        Button(onClick = { engine?.switchCamera() }, colors = ButtonDefaults.buttonColors(containerColor = Panel)) { Text("↻") }
                    }
                }
                Button(onClick = onEnd, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB83245))) { Text(if (call.incoming) "Отклонить" else "Завершить") }
            }
        }
    }
}

@Composable
private fun CallVideo(track: org.webrtc.VideoTrack?, egl: org.webrtc.EglBase.Context, modifier: Modifier, mirror: Boolean) {
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context).apply { init(egl, null); setEnableHardwareScaler(true); setMirror(mirror) } }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    DisposableEffect(track, renderer) { track?.addSink(renderer); onDispose { track?.removeSink(renderer) } }
    AndroidView(factory = { renderer }, modifier = modifier)
}

@Composable
private fun AuthScreen(
    username: String, onUsername: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    displayName: String, onDisplayName: (String) -> Unit,
    register: Boolean, onRegister: (Boolean) -> Unit,
    loading: Boolean, error: String?, onSubmit: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(
        Modifier.fillMaxWidth().widthIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DtrLogo(64)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("DTR", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Text("МЕССЕНДЖЕР", color = Accent, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(if (register) "Создать аккаунт" else "С возвращением", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (register) NativeField(displayName, onDisplayName, "Имя")
        NativeField(username, onUsername, "Логин")
        OutlinedTextField(
            value = password, onValueChange = onPassword, modifier = Modifier.fillMaxWidth(),
            label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            colors = fieldColors()
        )
        error?.let { Text(it, color = Color(0xFFFF7777), modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onSubmit, enabled = !loading && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White) else Text(if (register) "Зарегистрироваться" else "Войти", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { onRegister(!register) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Регистрация", color = Muted)
        }
        Text("Ваши существующие чаты и аккаунт остаются на сервере DTR.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp))
      }
    }
}

@Composable
private fun NativeField(value: String, onValue: (String) -> Unit, label: String) {
    OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, colors = fieldColors())
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Accent, unfocusedBorderColor = Muted.copy(alpha = .35f),
    focusedLabelColor = Accent, unfocusedLabelColor = Muted,
    focusedContainerColor = Panel, unfocusedContainerColor = Panel
)


@Composable
private fun DtrLogo(size: Int) {
    Image(
        painter = painterResource(id = R.drawable.ic_dtr_logo),
        contentDescription = "Логотип DTR",
        modifier = Modifier.size(size.dp)
    )
}

@Composable
private fun NativeBottomNav(page: String, onSelect: (String) -> Unit) {
    val items = listOf(
        Triple("friends", "♙", "Друзья"),
        Triple("feed", "▤", "Лента"),
        Triple("chats", "▣", "Чаты"),
        Triple("stories", "◉", "Истории"),
        Triple("settings", "⚙", "Ещё")
    )
    Row(
        Modifier.fillMaxWidth().background(Panel).navigationBarsPadding().padding(horizontal = 5.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items.forEach { (key, glyph, label) ->
            val selected = page == key
            TextButton(
                onClick = { onSelect(key) },
                modifier = Modifier.weight(1f).background(
                    if (selected) Accent.copy(alpha = .14f) else Color.Transparent,
                    RoundedCornerShape(13.dp)
                ),
                contentPadding = PaddingValues(horizontal = 1.dp, vertical = 5.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(glyph, color = if (selected) Accent else Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(label, color = if (selected) Accent else Muted, fontSize = 9.sp, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun NativeFeaturePage(
    page: String,
    token: String,
    api: suspend (String, String, JSONObject?) -> JSONObject,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    var rows by remember(page) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var input by remember(page) { mutableStateOf("") }
    var secondary by remember(page) { mutableStateOf("") }
    var searchResults by remember(page) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var incomingRows by remember(page) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var outgoingRows by remember(page) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var favoriteIds by remember(page) { mutableStateOf<Set<String>>(emptySet()) }
    var favoritesOnly by remember(page) { mutableStateOf(false) }
    var busy by remember(page) { mutableStateOf(false) }
    var error by remember(page) { mutableStateOf<String?>(null) }
    var info by remember(page) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val title = when (page) {
        "friends" -> "Друзья и контакты"
        "feed" -> "Общая лента"
        "stories" -> "Истории"
        "notes" -> "Мои заметки"
        "settings" -> "Настройки"
        "support" -> "Поддержка DTR"
        "admin" -> "Панель управления"
        else -> "DTR"
    }
    suspend fun refresh() {
        busy = true; error = null
        try {
            val path = when (page) {
                "friends" -> "/api/contacts"
                "feed" -> "/api/feed"
                "stories" -> "/api/stories"
                "notes" -> "/api/notes"
                "support" -> "/api/support/tickets"
                "admin" -> "/api/admin/users"
                else -> "/api/me"
            }
            val data = api(path, "GET", null)
            val key = when (page) {
                "friends" -> "friends"
                "feed" -> "posts"
                "stories" -> "stories"
                "notes" -> "notes"
                "support" -> "tickets"
                "admin" -> "users"
                else -> "user"
            }
            val arr = data.optJSONArray(key)
            rows = if (arr != null) (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                else if (page == "settings") listOf(data.optJSONObject("user") ?: JSONObject())
                else emptyList()
            if (page == "friends") {
                incomingRows = data.optJSONArray("incoming")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
                outgoingRows = data.optJSONArray("outgoing")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
                favoriteIds = data.optJSONArray("favorites")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") } }?.toSet() ?: emptySet()
            }
        } catch (e: Exception) { error = e.message ?: "Не удалось загрузить раздел" }
        finally { busy = false }
    }
    LaunchedEffect(page) { refresh() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White) }
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { refresh() } }) { Text("↻", color = Accent, fontSize = 20.sp) }
        }
        when (page) {
            "friends" -> {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(value=input, onValueChange={input=it}, modifier=Modifier.weight(1f), label={Text("Логин (от 2 символов)")}, singleLine=true, colors=fieldColors())
                    TextButton(onClick={scope.launch {
                        if(input.trim().length<2){error="Введи минимум 2 символа";return@launch}
                        try { val d=api("/api/users/search?q="+android.net.Uri.encode(input.trim()),"GET",null); val a=d.optJSONArray("users")?:JSONArray(); searchResults=(0 until a.length()).mapNotNull{a.optJSONObject(it)}; error=null }
                        catch(e:Exception){error=e.message}
                    }}) { Text("Найти",color=Accent) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                    Text("${rows.size} друзей · ${incomingRows.size} входящих",color=Muted,fontSize=12.sp)
                    TextButton(onClick={favoritesOnly=!favoritesOnly}) { Text(if(favoritesOnly) "★ Избранные" else "☆ Все друзья",color=Accent) }
                }
                LazyColumn(Modifier.weight(1f), contentPadding=PaddingValues(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(searchResults.isNotEmpty()) item { Text("Результаты поиска",color=Accent,fontWeight=FontWeight.SemiBold) }
                    items(searchResults, key={"search-"+it.optString("id")}) { u ->
                        val id=u.optString("id")
                        val isFriend=rows.any{it.optString("id")==id}
                        val incoming=incomingRows.firstOrNull{it.optString("id")==id}
                        val outgoing=outgoingRows.any{it.optString("id")==id}
                        FeatureRow(u.optString("displayName",u.optString("username")), "@"+u.optString("username"),
                            when { isFriend -> "Уже друг"; incoming!=null -> "Принять"; outgoing -> "Отправлена"; else -> "＋ Добавить" }) {
                            scope.launch { try {
                                when {
                                    isFriend -> info="Этот пользователь уже в друзьях"
                                    incoming!=null -> api("/api/friends/requests/"+incoming.optString("requestId"),"PATCH",JSONObject().put("action","accept"))
                                    outgoing -> info="Заявка уже отправлена"
                                    else -> api("/api/friends/request","POST",JSONObject().put("userId",id))
                                }
                                info=if(incoming!=null) "Заявка принята" else if(!isFriend&&!outgoing) "Заявка отправлена" else info
                                refresh(); searchResults=emptyList()
                            } catch(e:Exception){error=e.message} }
                        }
                    }
                    if(incomingRows.isNotEmpty()) item { Text("Входящие заявки · ${incomingRows.size}",color=Accent,fontWeight=FontWeight.SemiBold) }
                    items(incomingRows,key={"incoming-"+it.optString("requestId")}) { u ->
                        Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(u.optString("displayName"),color=Color.White,fontWeight=FontWeight.SemiBold); Text("@"+u.optString("username"),color=Muted,fontSize=12.sp) }
                            TextButton(onClick={scope.launch{try{api("/api/friends/requests/"+u.optString("requestId"),"PATCH",JSONObject().put("action","accept"));refresh();info="Заявка принята"}catch(e:Exception){error=e.message}}}){Text("Принять",color=Accent)}
                            TextButton(onClick={scope.launch{try{api("/api/friends/requests/"+u.optString("requestId"),"PATCH",JSONObject().put("action","reject"));refresh()}catch(e:Exception){error=e.message}}}){Text("Отклонить",color=Muted)}
                        }
                    }
                    if(outgoingRows.isNotEmpty()) item { Text("Исходящие заявки · ${outgoingRows.size}",color=Accent,fontWeight=FontWeight.SemiBold) }
                    items(outgoingRows,key={"outgoing-"+it.optString("id")}) { u ->
                        FeatureRow(u.optString("displayName"),"@"+u.optString("username"),"Отменить") {
                            scope.launch { try { api("/api/friends/"+u.optString("id"),"DELETE",null); refresh() } catch(e:Exception){error=e.message} }
                        }
                    }
                    val visibleFriends=if(favoritesOnly) rows.filter{favoriteIds.contains(it.optString("id"))} else rows
                    if(visibleFriends.isNotEmpty()) item { Text(if(favoritesOnly) "Избранные контакты" else "Мои друзья",color=Accent,fontWeight=FontWeight.SemiBold) }
                    items(visibleFriends,key={"friend-"+it.optString("id")}) { u ->
                        val id=u.optString("id"); val favorite=favoriteIds.contains(id)
                        Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(start=4.dp)) { Text(u.optString("displayName"),color=Color.White,fontWeight=FontWeight.SemiBold); Text("@"+u.optString("username"),color=Muted,fontSize=12.sp) }
                            TextButton(onClick={scope.launch{try{api("/api/favorites/"+id,if(favorite)"DELETE" else "POST",if(favorite)null else JSONObject());favoriteIds=if(favorite)favoriteIds-id else favoriteIds+id;info=if(favorite)"Убрано из избранного" else "Добавлено в избранное"}catch(e:Exception){error=e.message}}}){Text(if(favorite)"★" else "☆",color=Accent,fontSize=18.sp)}
                            TextButton(onClick={scope.launch{try{api("/api/friends/"+id,"DELETE",null);refresh();info="Удалено из друзей"}catch(e:Exception){error=e.message}}}){Text("Удалить",color=Muted,fontSize=11.sp)}
                        }
                    }
                    if(rows.isEmpty()&&incomingRows.isEmpty()&&outgoingRows.isEmpty()&&searchResults.isEmpty()) item { Text("Пока нет контактов. Найди пользователя по логину и отправь заявку.",color=Muted,modifier=Modifier.padding(12.dp)) }
                }
            }
            "feed" -> {
                OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.fillMaxWidth().padding(12.dp),label={Text("Написать публикацию")},minLines=2,colors=fieldColors())
                Button(onClick={scope.launch{if(input.isNotBlank())try{api("/api/feed","POST",JSONObject().put("body",input));input="";refresh()}catch(e:Exception){error=e.message}}},modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),enabled=input.isNotBlank()) { Text("Опубликовать") }
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                    items(rows,key={it.optString("id")}){p->
                        Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(16.dp)).padding(14.dp)){
                            Text(p.optString("display_name",p.optString("username")),color=Accent,fontWeight=FontWeight.Bold)
                            Text(p.optString("body"),color=Color.White,modifier=Modifier.padding(vertical=10.dp))
                            TextButton(onClick={scope.launch{try{api("/api/feed/"+p.optString("id")+"/like","POST",JSONObject());refresh()}catch(e:Exception){error=e.message}}}){Text((if(p.optBoolean("liked"))"♥ " else "♡ ")+p.optInt("likes")+" · "+p.optInt("comments")+" комментариев",color=Accent)}
                        }
                    }
                }
            }
            "stories" -> {
                OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.fillMaxWidth().padding(12.dp),label={Text("Текст истории")},colors=fieldColors())
                Button(onClick={scope.launch{if(input.isNotBlank())try{api("/api/stories","POST",JSONObject().put("body",input));input="";refresh()}catch(e:Exception){error=e.message}}},modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),enabled=input.isNotBlank()){Text("Опубликовать историю")}
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    items(rows,key={it.optString("id")}){s->FeatureRow(s.optString("display_name",s.optString("username")),s.optString("body").ifBlank{"Медиа-история"}, "История"){info="Медиа и просмотр историй появятся в следующем обновлении"}}
                }
            }
            "notes" -> {
                OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=6.dp),label={Text("Заголовок")},singleLine=true,colors=fieldColors())
                OutlinedTextField(value=secondary,onValueChange={secondary=it},modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=6.dp),label={Text("Текст заметки")},minLines=2,colors=fieldColors())
                Button(onClick={scope.launch{try{api("/api/notes","POST",JSONObject().put("title",input).put("body",secondary));input="";secondary="";refresh()}catch(e:Exception){error=e.message}}},modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),enabled=input.isNotBlank()||secondary.isNotBlank()){Text("Сохранить заметку")}
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    items(rows,key={it.optString("id")}){n->FeatureRow(n.optString("title").ifBlank{"Без названия"},n.optString("body"),"Удалить"){scope.launch{try{api("/api/notes/"+n.optString("id"),"DELETE",null);refresh()}catch(e:Exception){error=e.message}}}}
                }
            }
            "settings" -> {
                val user=rows.firstOrNull()?:JSONObject()
                Text("Аккаунт",color=Accent,fontWeight=FontWeight.Bold,modifier=Modifier.padding(16.dp))
                Text(user.optString("displayName")+"  @"+user.optString("username"),color=Color.White,modifier=Modifier.padding(horizontal=16.dp))
                OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.fillMaxWidth().padding(12.dp),label={Text("Статус или эмодзи")},colors=fieldColors())
                Button(onClick={scope.launch{try{api("/api/profile/status","PATCH",JSONObject().put("statusEmoji",input));info="Статус обновлён";refresh()}catch(e:Exception){error=e.message}}},modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)){Text("Сохранить статус")}
                listOf("notes" to "Мои заметки","support" to "Центр поддержки","admin" to "Панель управления").forEach{(k,v)->TextButton(onClick={onNavigate(k)}){Text(v,color=Accent)}}
                Button(onClick = { scope.launch {
                    updateStatus = "Проверяем обновления…"; updateUrl = null
                    try {
                        val req = Request.Builder().url("https://api.github.com/repos/Hqhsha/-dtr-messenger/releases?per_page=30").header("Accept", "application/vnd.github+json").header("User-Agent", "DTR-Android").build()
                        val response = withContext(Dispatchers.IO) { http.newCall(req).execute() }
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException("Ошибка проверки: " + response.code)
                        val releases = JSONArray(raw)
                        val release = (0 until releases.length()).map { releases.getJSONObject(it) }.firstOrNull { it.optString("tag_name").startsWith("native-v") }
                        if (release == null) updateStatus = "Пока нет опубликованных обновлений"
                        else {
                            val assetArray = release.optJSONArray("assets") ?: JSONArray()
                            val asset = (0 until assetArray.length()).map { assetArray.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk", true) }
                            val latest = release.optString("tag_name").removePrefix("native-v")
                            val current = BuildConfig.VERSION_NAME.substringBefore("-")
                            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
                            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
                            var newer = false
                            for (i in 0..2) {
                                val a = latestParts.getOrElse(i) { 0 }; val b = currentParts.getOrElse(i) { 0 }
                                if (a != b) { newer = a > b; break }
                            }
                            if (newer && asset != null) {
                                updateStatus = "Доступна DTR " + latest
                                updateUrl = asset.optString("browser_download_url")
                            } else updateStatus = "Установлена актуальная версия " + BuildConfig.VERSION_NAME
                        }
                    } catch (e: Exception) { updateStatus = e.message ?: "Не удалось проверить обновления" }
                } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent), shape = RoundedCornerShape(14.dp)) { Text("Проверить обновления") }
                updateStatus?.let { Text(it, color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                updateUrl?.let { url -> Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp)) { Text("Скачать новую версию") } }
                Spacer(Modifier.weight(1f))
            }
            "support" -> {
                OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.fillMaxWidth().padding(12.dp),label={Text("Тема обращения")},singleLine=true,colors=fieldColors())
                OutlinedTextField(value=secondary,onValueChange={secondary=it},modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),label={Text("Сообщение")},minLines=2,colors=fieldColors())
                Button(onClick={scope.launch{try{api("/api/support/tickets","POST",JSONObject().put("subject",input).put("body",secondary).put("category","question"));input="";secondary="";info="Обращение создано";refresh()}catch(e:Exception){error=e.message}}},modifier=Modifier.fillMaxWidth().padding(12.dp),enabled=input.isNotBlank()&&secondary.isNotBlank()){Text("Отправить в поддержку")}
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(12.dp)){items(rows,key={it.optString("id")}){t->FeatureRow(t.optString("subject"),t.optString("status")+" · "+t.optString("last_message"),"Обновить"){scope.launch{refresh()}}}}
            }
            "admin" -> {
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    items(rows,key={it.optString("id")}){u->FeatureRow(u.optString("displayName"),"@"+u.optString("username")+" · "+u.optString("role"),"Пользователь"){info="Управление ролями пока не добавлено в нативный интерфейс"}}
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Accent)
        error?.let{Text(it,color=Color(0xFFFF7777),modifier=Modifier.padding(10.dp))}
        info?.let{Text(it,color=Accent,modifier=Modifier.padding(10.dp))}
        if (page=="friends" || page=="feed" || page=="stories" || page=="notes" || page=="support" || page=="admin") {
            Row(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding(),horizontalArrangement=Arrangement.SpaceEvenly){
                TextButton(onClick=onBack){Text("К чатам",color=Muted)}
                TextButton(onClick={scope.launch{refresh()}}){Text("Обновить",color=Accent)}
                if(page=="settings"){TextButton(onClick={onBack}){Text("Готово",color=Accent)}}
            }
        }
    }
}

@Composable
private fun FeatureRow(title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(12.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){
            Text(title,color=Color.White,fontWeight=FontWeight.SemiBold)
            if(subtitle.isNotBlank()) Text(subtitle,color=Muted,fontSize=12.sp)
        }
        TextButton(onClick=onAction){Text(action,color=Accent,fontSize=11.sp)}
    }
}
