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
const publicUser = (u: any) => ({ id: String(u.id), username: u.username, displayName: u.display_name, bio: u.bio || "", avatarUrl: u.avatar_url || null });
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
    const user = publicUser(q.rows[0]); res.status(201).json({ token: signToken(user.id), user });
  } catch (e: any) { if (e.code === "23505") return res.status(409).json({ error: "Этот логин уже занят" }); console.error(e); res.status(500).json({ error: "Не удалось создать аккаунт" }); }
});
app.post("/api/auth/login", async (req, res) => {
  const username = String(req.body.username || "").trim();
  const q = await pool.query("SELECT * FROM users WHERE LOWER(username)=LOWER($1)", [username]);
  const u = q.rows[0];
  if (!u || !(await bcrypt.compare(String(req.body.password || ""), u.password_hash))) return res.status(401).json({ error: "Неверный логин или пароль" });
  const user = publicUser(u); res.json({ token: signToken(user.id), user });
});
app.get("/api/me", auth, async (req: AuthedRequest, res) => {
  const q = await pool.query("SELECT * FROM users WHERE id=$1", [req.userId]);
  if (!q.rowCount) return res.status(404).json({ error: "Пользователь не найден" });
  res.json({ user: publicUser(q.rows[0]) });
});
app.patch("/api/profile", auth, async (req: AuthedRequest, res) => {
  const displayName = String(req.body.displayName || "").trim().slice(0, 40);
  const bio = String(req.body.bio || "").trim().slice(0, 160);
  if (!displayName) return res.status(400).json({ error: "Укажи отображаемое имя" });
  const q = await pool.query("UPDATE users SET display_name=$1,bio=$2 WHERE id=$3 RETURNING *", [displayName, bio, req.userId]);
  res.json({ user: publicUser(q.rows[0]) });
});
const storage = multer.diskStorage({ destination: uploadDir, filename: (req, file, cb) => cb(null, `${(req as AuthedRequest).userId}-${Date.now()}${path.extname(file.originalname).toLowerCase()}`) });
const avatarUpload = multer({ storage, limits: { fileSize: 5 * 1024 * 1024 }, fileFilter: (_req, file, cb) => cb(null, ["image/png","image/jpeg","image/webp","image/gif"].includes(file.mimetype)) });
app.post("/api/profile/avatar", auth, avatarUpload.single("avatar"), async (req: AuthedRequest, res) => {
  if (!req.file) return res.status(400).json({ error: "Выбери PNG, JPG, WEBP или GIF до 5 МБ" });
  const url = `/uploads/${req.file.filename}`;
  const q = await pool.query("UPDATE users SET avatar_url=$1 WHERE id=$2 RETURNING *", [url, req.userId]);
  res.json({ user: publicUser(q.rows[0]) });
});

app.get("/api/users/search", auth, async (req: AuthedRequest, res) => {
  const term = String(req.query.q || "").trim().replace(/[%_]/g, "");
  if (term.length < 2) return res.json({ users: [] });
  const q = await pool.query("SELECT id,username,display_name,bio,avatar_url FROM users WHERE id<>$1 AND username ILIKE $2 ORDER BY username LIMIT 20", [req.userId, `%${term}%`]);
  res.json({ users: q.rows.map(publicUser) });
});
app.get("/api/chats", auth, async (req: AuthedRequest, res) => {
  const q = await pool.query(`SELECT c.id, c.created_at, u.id AS other_id,u.username,u.display_name,u.bio,u.avatar_url,
    (SELECT body FROM messages WHERE chat_id=c.id ORDER BY created_at DESC LIMIT 1) AS last_message,
    (SELECT created_at FROM messages WHERE chat_id=c.id ORDER BY created_at DESC LIMIT 1) AS last_message_at
    FROM chats c JOIN chat_members mine ON mine.chat_id=c.id AND mine.user_id=$1
    JOIN chat_members other ON other.chat_id=c.id AND other.user_id<>$1 JOIN users u ON u.id=other.user_id
    ORDER BY COALESCE(last_message_at,c.created_at) DESC`, [req.userId]);
  res.json({ chats: q.rows.map(r => ({ chatId: String(r.id), user: publicUser({ id:r.other_id, username:r.username, display_name:r.display_name, bio:r.bio, avatar_url:r.avatar_url }), lastMessage:r.last_message, updatedAt:r.last_message_at || r.created_at })) });
});
app.post("/api/chats/open", auth, async (req: AuthedRequest, res) => {
  const otherId = String(req.body.userId || "");
  if (!/^\d+$/.test(otherId) || otherId === req.userId) return res.status(400).json({ error: "Некорректный пользователь" });
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const exists = await client.query("SELECT id FROM users WHERE id=$1", [otherId]);
    if (!exists.rowCount) { await client.query("ROLLBACK"); return res.status(404).json({ error: "Пользователь не найден" }); }
    const found = await client.query(`SELECT c.id FROM chats c JOIN chat_members a ON a.chat_id=c.id AND a.user_id=$1 JOIN chat_members b ON b.chat_id=c.id AND b.user_id=$2 WHERE (SELECT COUNT(*) FROM chat_members m WHERE m.chat_id=c.id)=2 LIMIT 1`, [req.userId, otherId]);
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
app.get("/api/chats/:chatId/messages", auth, async (req: AuthedRequest, res) => {
  const member = await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2", [req.params.chatId, req.userId]);
  if (!member.rowCount) return res.status(403).json({ error: "Нет доступа к чату" });
  const q = await pool.query(`SELECT m.id,m.body,m.created_at,m.sender_id,u.username,u.display_name FROM messages m JOIN users u ON u.id=m.sender_id WHERE m.chat_id=$1 ORDER BY m.created_at ASC LIMIT 200`, [req.params.chatId]);
  await pool.query("UPDATE chat_members SET last_read_at=NOW() WHERE chat_id=$1 AND user_id=$2", [req.params.chatId, req.userId]);
  res.json({ messages: q.rows.map(m => ({ id:String(m.id), body:m.body, createdAt:m.created_at, senderId:String(m.sender_id), username:m.username, displayName:m.display_name })) });
});
app.post("/api/chats/:chatId/messages", auth, async (req: AuthedRequest, res) => {
  const body = String(req.body.body || "").trim();
  if (!body || body.length > 4000) return res.status(400).json({ error: "Сообщение должно быть от 1 до 4000 символов" });
  const member = await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2", [req.params.chatId, req.userId]);
  if (!member.rowCount) return res.status(403).json({ error: "Нет доступа к чату" });
  const q = await pool.query("INSERT INTO messages(chat_id,sender_id,body) VALUES($1,$2,$3) RETURNING *", [req.params.chatId, req.userId, body]);
  const message = { id:String(q.rows[0].id), chatId:String(q.rows[0].chat_id), senderId:String(q.rows[0].sender_id), body:q.rows[0].body, createdAt:q.rows[0].created_at };
  const members = await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1", [req.params.chatId]);
  for (const m of members.rows) io.to(`user:${m.user_id}`).emit("chat:message", message);
  res.status(201).json({ message });
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
httpServer.listen(port, () => console.log(`дтр API listening on :${port}`));
