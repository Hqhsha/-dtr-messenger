import "dotenv/config";
import express from "express";
import cors from "cors";
import multer from "multer";
import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import { Pool } from "pg";
import { createServer } from "node:http";
import { Server } from "socket.io";
import path from "node:path";
import fs from "node:fs";

const app = express();
const httpServer = createServer(app);
const allowedOrigins = process.env.CLIENT_ORIGIN
  ? process.env.CLIENT_ORIGIN.split(",").map((origin) => origin.trim())
  : true;
const io = new Server(httpServer, { cors: { origin: allowedOrigins } });
const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ...(process.env.NODE_ENV === "production" ? { ssl: { rejectUnauthorized: false } } : {}),
});
const JWT_SECRET = process.env.JWT_SECRET;
const schemaReady = pool.query("ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_data BYTEA, ADD COLUMN IF NOT EXISTS avatar_mime TEXT, ADD COLUMN IF NOT EXISTS age SMALLINT, ADD COLUMN IF NOT EXISTS age_public BOOLEAN NOT NULL DEFAULT FALSE; ALTER TABLE chats ADD COLUMN IF NOT EXISTS chat_type TEXT NOT NULL DEFAULT 'direct'; ALTER TABLE chats ADD COLUMN IF NOT EXISTS title VARCHAR(60)");

if (!JWT_SECRET || JWT_SECRET.length < 32) throw new Error("JWT_SECRET must contain at least 32 characters");
app.use(cors({ origin: allowedOrigins }));
app.use(express.json({ limit: "1mb" }));
const uploadDir = path.resolve("uploads");
fs.mkdirSync(uploadDir, { recursive: true });
app.use("/uploads", express.static(uploadDir));

type AuthedRequest = express.Request & { userId?: string };
function signToken(id: string) { return jwt.sign({ sub: id }, JWT_SECRET!, { expiresIn: "7d" }); }
function auth(req: AuthedRequest, res: express.Response, next: express.NextFunction) {
  const token = req.headers.authorization?.replace(/^Bearer\s+/i, "");
  if (!token) return res.status(401).json({ error: "Требуется вход" });
  try { req.userId = String((jwt.verify(token, JWT_SECRET!) as jwt.JwtPayload).sub); next(); }
  catch { return res.status(401).json({ error: "Сессия истекла" }); }
}
const publicUser = (u: any, isOwner = false) => ({ id: String(u.id), username: u.username, displayName: u.display_name, bio: u.bio || "", avatarUrl: u.avatar_url ? "/api/users/" + u.id + "/avatar" : null, age: isOwner || u.age_public ? (u.age ?? null) : null, agePublic: Boolean(u.age_public) });
app.get("/api/health", (_req, res) => res.json({ ok: true }));

app.post("/api/auth/register", async (req, res) => {
  const username = String(req.body.username || "").trim();
  const password = String(req.body.password || "");
  const displayName = String(req.body.displayName || username).trim().slice(0, 40);
  if (!/^[A-Za-z0-9_]{3,24}$/.test(username)) return res.status(400).json({ error: "Логин: 3–24 символа, латиница, цифры и _" });
  if (password.length < 8 || password.length > 72) return res.status(400).json({ error: "Пароль должен содержать от 8 до 72 символов" });
  try {
    const hash = await bcrypt.hash(password, 12);
    const q = await pool.query("INSERT INTO users(username,password_hash,display_name) VALUES($1,$2,$3) RETURNING *", [username, hash, displayName || username]);
    const user = publicUser(q.rows[0], true); res.status(201).json({ token: signToken(user.id), user });
  } catch (e: any) { if (e.code === "23505") return res.status(409).json({ error: "Этот логин уже занят" }); console.error(e); res.status(500).json({ error: "Не удалось создать аккаунт" }); }
});
app.post("/api/auth/login", async (req, res) => {
  const username = String(req.body.username || "").trim();
  const q = await pool.query("SELECT * FROM users WHERE LOWER(username)=LOWER($1)", [username]);
  const u = q.rows[0];
  if (!u || !(await bcrypt.compare(String(req.body.password || ""), u.password_hash))) return res.status(401).json({ error: "Неверный логин или пароль" });
  const user = publicUser(u, true); res.json({ token: signToken(user.id), user });
});
app.get("/api/me", auth, async (req: AuthedRequest, res) => {
  const q = await pool.query("SELECT * FROM users WHERE id=$1", [req.userId]);
  if (!q.rowCount) return res.status(404).json({ error: "Пользователь не найден" });
  res.json({ user: publicUser(q.rows[0], true) });
});
app.patch("/api/profile", auth, async (req: AuthedRequest, res) => {
  const displayName = String(req.body.displayName || "").trim().slice(0, 40);
  const bio = String(req.body.bio || "").trim().slice(0, 160);
  const age = req.body.age === null || req.body.age === "" || req.body.age === undefined ? null : Number(req.body.age);
  const agePublic = Boolean(req.body.agePublic);
  if (!displayName) return res.status(400).json({ error: "Укажи отображаемое имя" });
  if (age !== null && (!Number.isInteger(age) || age < 13 || age > 99)) return res.status(400).json({ error: "Возраст должен быть от 13 до 99 лет" });
  const q = await pool.query("UPDATE users SET display_name=$1,bio=$2,age=$3,age_public=$4 WHERE id=$5 RETURNING *", [displayName, bio, age, agePublic, req.userId]);
  res.json({ user: publicUser(q.rows[0], true) });
});
const storage = multer.diskStorage({ destination: uploadDir, filename: (req, file, cb) => cb(null, `${(req as AuthedRequest).userId}-${Date.now()}${path.extname(file.originalname).toLowerCase()}`) });
const avatarUpload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 5 * 1024 * 1024 }, fileFilter: (_req, file, cb) => cb(null, ["image/png","image/jpeg","image/webp","image/gif"].includes(file.mimetype)) });
const mediaUpload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 12 * 1024 * 1024 }, fileFilter: (_req, file, cb) => cb(null, ["image/png","image/jpeg","image/webp","image/gif","audio/webm","audio/ogg","audio/mp4","audio/mpeg","audio/wav","audio/x-wav","audio/mp4a-latm"].includes(file.mimetype.split(";")[0])) });
app.post("/api/profile/avatar", auth, avatarUpload.single("avatar"), async (req: AuthedRequest, res) => {
  if (!req.file) return res.status(400).json({ error: "Выбери PNG, JPG, WEBP или GIF до 5 МБ" });
  const q = await pool.query("UPDATE users SET avatar_url=$1,avatar_data=$2,avatar_mime=$3 WHERE id=$4 RETURNING *", [`db:${Date.now()}`, req.file.buffer, req.file.mimetype, req.userId]);
  res.json({ user: publicUser(q.rows[0], true) });
});
app.get("/api/users/:userId/avatar", async (req, res) => {
  if (!/^\\d+$/.test(req.params.userId)) return res.sendStatus(404);
  try {
    const q = await pool.query("SELECT avatar_url,avatar_data,avatar_mime FROM users WHERE id=$1", [req.params.userId]);
    if (!q.rowCount || !q.rows[0].avatar_url) return res.sendStatus(404);
    const user = q.rows[0];
    if (user.avatar_data) { res.setHeader("Content-Type", user.avatar_mime || "application/octet-stream"); res.setHeader("Cache-Control", "public, max-age=300"); return res.send(user.avatar_data); }
    if (String(user.avatar_url).startsWith("/uploads/")) return res.redirect(user.avatar_url);
    return res.sendStatus(404);
  } catch (e) { console.error("GET avatar failed:", e); return res.sendStatus(500); }
});

app.get("/api/users/search", auth, async (req: AuthedRequest, res) => {
  const term = String(req.query.q || "").trim().replace(/[%_]/g, "");
  if (term.length < 2) return res.json({ users: [] });
  const q = await pool.query("SELECT id,username,display_name,bio,avatar_url FROM users WHERE id<>$1 AND username ILIKE $2 ORDER BY username LIMIT 20", [req.userId, `%${term}%`]);
  res.json({ users: q.rows.map((u: any) => publicUser(u)) });
});
// Chat list query sorts by the latest message timestamp without relying on a SELECT alias.
app.get("/api/chats", auth, async (req: AuthedRequest, res) => {
  try {
    const q = await pool.query(`SELECT c.id,c.chat_type,c.title,c.created_at,
      (SELECT body FROM messages WHERE chat_id=c.id ORDER BY created_at DESC LIMIT 1) AS last_message,
      (SELECT created_at FROM messages WHERE chat_id=c.id ORDER BY created_at DESC LIMIT 1) AS last_message_at,
      (SELECT json_agg(json_build_object('id',u.id,'username',u.username,'display_name',u.display_name,'bio',u.bio,'avatar_url',u.avatar_url,'age',u.age,'age_public',u.age_public) ORDER BY u.display_name) FROM chat_members cm JOIN users u ON u.id=cm.user_id WHERE cm.chat_id=c.id) AS members
      FROM chats c JOIN chat_members mine ON mine.chat_id=c.id AND mine.user_id=$1
      ORDER BY COALESCE((SELECT MAX(created_at) FROM messages WHERE chat_id=c.id),c.created_at) DESC`, [req.userId]);
    res.json({ chats: q.rows.map(r => {
      const members = (r.members || []).map((u: any) => publicUser(u, String(u.id) === String(req.userId)));
      const other = members.find((u: any) => u.id !== String(req.userId));
      return { chatId:String(r.id), type:r.chat_type || "direct", title:r.title, user:other || null, members, lastMessage:r.last_message, updatedAt:r.last_message_at || r.created_at };
    }) });
  } catch (e) { console.error("GET /api/chats failed:",e); res.status(500).json({ error:"Не удалось загрузить список чатов" }); }
});
app.post("/api/chats/open", auth, async (req: AuthedRequest, res) => {
  const otherId = String(req.body.userId || "");
  if (!/^\d+$/.test(otherId) || otherId === req.userId) return res.status(400).json({ error: "Некорректный пользователь" });
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const exists = await client.query("SELECT id FROM users WHERE id=$1", [otherId]);
    if (!exists.rowCount) { await client.query("ROLLBACK"); return res.status(404).json({ error: "Пользователь не найден" }); }
    const found = await client.query(`SELECT c.id FROM chats c JOIN chat_members a ON a.chat_id=c.id AND a.user_id=$1 JOIN chat_members b ON b.chat_id=c.id AND b.user_id=$2 WHERE c.chat_type='direct' AND (SELECT COUNT(*) FROM chat_members m WHERE m.chat_id=c.id)=2 LIMIT 1`, [req.userId, otherId]);
    let chatId = found.rows[0]?.id;
    if (!chatId) {
      const created = await client.query("INSERT INTO chats DEFAULT VALUES RETURNING id");
      chatId = created.rows[0].id;
      await client.query("INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2),($1,$3)", [chatId, req.userId, otherId]);
    }
    await client.query("COMMIT"); res.json({ chatId: String(chatId) });
  } catch (e) { await client.query("ROLLBACK"); console.error(e); res.status(500).json({ error: "Не удалось открыть чат" }); }
  finally { client.release(); }
});
app.post("/api/chats/group", auth, async (req: AuthedRequest, res) => {
  const title = String(req.body.title || "").trim().slice(0,60);
  const rawIds = Array.isArray(req.body.userIds) ? req.body.userIds.map(String) : [];
  const userIds = [...new Set(rawIds.filter((id: string) => /^[0-9]+$/.test(id) && id !== req.userId))];
  if (!title) return res.status(400).json({error:"Укажи название группы"});
  if (userIds.length < 1 || userIds.length > 19) return res.status(400).json({error:"Добавь от 1 до 19 участников"});
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const found = await client.query("SELECT id FROM users WHERE id=ANY($1::bigint[])",[userIds]);
    if (found.rowCount !== userIds.length) { await client.query("ROLLBACK"); return res.status(400).json({error:"Один или несколько пользователей не найдены"}); }
    const created = await client.query("INSERT INTO chats(chat_type,title) VALUES('group',$1) RETURNING id",[title]);
    const chatId = created.rows[0].id;
    for (const id of [String(req.userId),...userIds]) await client.query("INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2)",[chatId,id]);
    await client.query("COMMIT");
    for (const id of [String(req.userId),...userIds]) io.to("user:"+id).emit("chat:refresh",{chatId:String(chatId)});
    res.status(201).json({chatId:String(chatId)});
  } catch(e) { await client.query("ROLLBACK"); console.error("Create group failed:",e); res.status(500).json({error:"Не удалось создать группу"}); }
  finally { client.release(); }
});
app.post("/api/chats/:chatId/members", auth, async (req: AuthedRequest, res) => {
  const userId = String(req.body.userId || "");
  if (!/^[0-9]+$/.test(userId)) return res.status(400).json({error:"Некорректный пользователь"});
  const membership = await pool.query("SELECT c.chat_type FROM chats c JOIN chat_members cm ON cm.chat_id=c.id WHERE c.id=$1 AND cm.user_id=$2",[req.params.chatId,req.userId]);
  if (!membership.rowCount) return res.status(403).json({error:"Нет доступа к группе"});
  if (membership.rows[0].chat_type !== "group") return res.status(400).json({error:"Добавлять участников можно только в группу"});
  const count = await pool.query("SELECT COUNT(*)::int AS count FROM chat_members WHERE chat_id=$1",[req.params.chatId]);
  if (count.rows[0].count >= 20) return res.status(400).json({error:"В группе максимум 20 участников"});
  const user = await pool.query("SELECT id FROM users WHERE id=$1",[userId]);
  if (!user.rowCount) return res.status(404).json({error:"Пользователь не найден"});
  await pool.query("INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2) ON CONFLICT DO NOTHING",[req.params.chatId,userId]);
  const members = await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1",[req.params.chatId]);
  for (const m of members.rows) io.to("user:"+m.user_id).emit("chat:refresh",{chatId:String(req.params.chatId)});
  res.json({ok:true});
});
app.get("/api/users/:userId/profile", auth, async (req: AuthedRequest, res) => {
  const targetId = String(req.params.userId);
  if (!/^[0-9]+$/.test(targetId)) return res.sendStatus(404);
  const q = await pool.query("SELECT id,username,display_name,bio,avatar_url,age,age_public FROM users WHERE id=$1",[targetId]);
  if (!q.rowCount) return res.status(404).json({error:"Пользователь не найден"});
  res.json({user:publicUser(q.rows[0],String(req.userId)===targetId)});
});
app.get("/api/chats/:chatId/messages", auth, async (req: AuthedRequest, res) => {
  const member = await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2", [req.params.chatId, req.userId]);
  if (!member.rowCount) return res.status(403).json({ error: "Нет доступа к чату" });
  const q = await pool.query(`SELECT m.id,m.body,m.message_type,m.created_at,m.sender_id,u.username,u.display_name FROM messages m JOIN users u ON u.id=m.sender_id WHERE m.chat_id=$1 ORDER BY m.created_at ASC LIMIT 200`, [req.params.chatId]);
  await pool.query("UPDATE chat_members SET last_read_at=NOW() WHERE chat_id=$1 AND user_id=$2", [req.params.chatId, req.userId]);
  res.json({ messages: q.rows.map(m => ({ id:String(m.id), body:m.body, messageType:m.message_type, mediaUrl:m.message_type === "text" ? null : `/api/messages/${m.id}/media`, createdAt:m.created_at, senderId:String(m.sender_id), username:m.username, displayName:m.display_name })) });
});
app.post("/api/chats/:chatId/messages", auth, async (req: AuthedRequest, res) => {
  const body = String(req.body.body || "").trim();
  if (!body || body.length > 4000) return res.status(400).json({ error: "Сообщение должно быть от 1 до 4000 символов" });
  const member = await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2", [req.params.chatId, req.userId]);
  if (!member.rowCount) return res.status(403).json({ error: "Нет доступа к чату" });
  const q = await pool.query("INSERT INTO messages(chat_id,sender_id,body) VALUES($1,$2,$3) RETURNING *", [req.params.chatId, req.userId, body]);
  const sender = await pool.query("SELECT username,display_name FROM users WHERE id=$1",[req.userId]);
  const message = { id:String(q.rows[0].id), chatId:String(q.rows[0].chat_id), senderId:String(q.rows[0].sender_id), body:q.rows[0].body, messageType:"text", mediaUrl:null, createdAt:q.rows[0].created_at, username:sender.rows[0]?.username, displayName:sender.rows[0]?.display_name };
  const members = await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1", [req.params.chatId]);
  for (const m of members.rows) io.to(`user:${m.user_id}`).emit("chat:message", message);
  res.status(201).json({ message });
});
app.post("/api/chats/:chatId/media", auth, mediaUpload.single("media"), async (req: AuthedRequest, res) => {
  const file = req.file;
  if (!file) return res.status(400).json({ error: "Выбери фото или запиши голосовое сообщение в поддерживаемом формате" });
  const isImage = file.mimetype.startsWith("image/"); const isAudio = file.mimetype.startsWith("audio/");
  if (!isImage && !isAudio) return res.status(415).json({ error: "Поддерживаются только изображения и аудио" });
  const member = await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2", [req.params.chatId, req.userId]);
  if (!member.rowCount) return res.status(403).json({ error: "Нет доступа к чату" });
  const caption = String(req.body.caption || "").trim().slice(0, 500); const body = caption || (isImage ? "Фото" : "Голосовое сообщение");
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const inserted = await client.query("INSERT INTO messages(chat_id,sender_id,body,message_type) VALUES($1,$2,$3,$4) RETURNING *", [req.params.chatId, req.userId, body, isImage ? "image" : "audio"]);
    const messageId = inserted.rows[0].id;
    const safeName = path.basename(file.originalname).replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 120) || (isImage ? "image" : "voice");
    await client.query("INSERT INTO message_media(message_id,mime_type,file_name,file_size,data) VALUES($1,$2,$3,$4,$5)", [messageId, file.mimetype, safeName, file.size, file.buffer]);
    await client.query("COMMIT");
    const sender = await pool.query("SELECT username,display_name FROM users WHERE id=$1",[req.userId]);
    const message = { id:String(messageId), chatId:String(req.params.chatId), senderId:String(req.userId), body, messageType:isImage ? "image" : "audio", mediaUrl:`/api/messages/${messageId}/media`, createdAt:inserted.rows[0].created_at, username:sender.rows[0]?.username, displayName:sender.rows[0]?.display_name };
    const members = await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1", [req.params.chatId]);
    for (const m of members.rows) io.to(`user:${m.user_id}`).emit("chat:message", message);
    res.status(201).json({ message });
  } catch (e) { await client.query("ROLLBACK"); console.error("POST /api/chats/:chatId/media failed:", e); res.status(500).json({ error: "Не удалось отправить файл" }); }
  finally { client.release(); }
});
app.get("/api/messages/:messageId/media", auth, async (req: AuthedRequest, res) => {
  const q = await pool.query(`SELECT mm.mime_type,mm.file_name,mm.file_size,mm.data FROM message_media mm JOIN messages m ON m.id=mm.message_id JOIN chat_members cm ON cm.chat_id=m.chat_id AND cm.user_id=$2 WHERE mm.message_id=$1`, [req.params.messageId, req.userId]);
  if (!q.rowCount) return res.status(404).json({ error: "Файл не найден или нет доступа" });
  const media = q.rows[0]; res.setHeader("Content-Type", media.mime_type); res.setHeader("Content-Length", String(media.file_size));
  res.setHeader("Content-Disposition", `inline; filename="${media.file_name}"`); res.setHeader("Cache-Control", "private, no-store"); res.setHeader("X-Content-Type-Options", "nosniff"); res.send(media.data);
});
io.use((socket, next) => {
  try { const token = socket.handshake.auth?.token; const payload = jwt.verify(token, JWT_SECRET!) as jwt.JwtPayload; socket.data.userId = String(payload.sub); next(); }
  catch { next(new Error("unauthorized")); }
});
io.on("connection", socket => socket.join(`user:${socket.data.userId}`));
// In production, serve the Vite build from the same origin as the API and Socket.IO.
const clientDist = path.resolve("dist");
if (fs.existsSync(clientDist)) {
  app.use(express.static(clientDist));
  app.get("*", (_req, res) => res.sendFile(path.join(clientDist, "index.html")));
}

const port = Number(process.env.PORT || 3001);
schemaReady.then(() => httpServer.listen(port, () => console.log(`дтр API listening on :${port}`))).catch(error => { console.error("Database schema initialization failed:", error); process.exit(1); });
