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
import webpush from "web-push";
import { randomBytes } from "node:crypto";

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
const VAPID_PUBLIC_KEY = process.env.VAPID_PUBLIC_KEY || "";
const VAPID_PRIVATE_KEY = process.env.VAPID_PRIVATE_KEY || "";
const VAPID_SUBJECT = process.env.VAPID_SUBJECT || "https://dtr-messenger-z1ee.onrender.com";
if (VAPID_PUBLIC_KEY && VAPID_PRIVATE_KEY) webpush.setVapidDetails(VAPID_SUBJECT,VAPID_PUBLIC_KEY,VAPID_PRIVATE_KEY);
const schemaReady = pool.query("ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_data BYTEA, ADD COLUMN IF NOT EXISTS avatar_mime TEXT, ADD COLUMN IF NOT EXISTS age SMALLINT, ADD COLUMN IF NOT EXISTS age_public BOOLEAN NOT NULL DEFAULT FALSE; ALTER TABLE chats ADD COLUMN IF NOT EXISTS chat_type TEXT NOT NULL DEFAULT 'direct'; ALTER TABLE chats ADD COLUMN IF NOT EXISTS title VARCHAR(60), ADD COLUMN IF NOT EXISTS description VARCHAR(500) NOT NULL DEFAULT '', ADD COLUMN IF NOT EXISTS creator_id BIGINT REFERENCES users(id) ON DELETE SET NULL, ADD COLUMN IF NOT EXISTS invite_code TEXT UNIQUE, ADD COLUMN IF NOT EXISTS avatar_data BYTEA, ADD COLUMN IF NOT EXISTS avatar_mime TEXT; CREATE TABLE IF NOT EXISTS push_subscriptions (id BIGSERIAL PRIMARY KEY,user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,endpoint TEXT NOT NULL UNIQUE,subscription JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()); CREATE INDEX IF NOT EXISTS push_subscriptions_user_id_idx ON push_subscriptions(user_id); ALTER TABLE push_subscriptions ENABLE ROW LEVEL SECURITY; REVOKE ALL ON TABLE push_subscriptions FROM anon, authenticated");

const featureSchemaReady = schemaReady.then(()=>pool.query("ALTER TABLE users ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'member'; ALTER TABLE users ADD COLUMN IF NOT EXISTS status_emoji TEXT; ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ; ALTER TABLE users ADD COLUMN IF NOT EXISTS theme TEXT NOT NULL DEFAULT 'dark'; ALTER TABLE user_stories ADD COLUMN IF NOT EXISTS media_data BYTEA, ADD COLUMN IF NOT EXISTS media_mime TEXT, ADD COLUMN IF NOT EXISTS media_name TEXT; CREATE TABLE IF NOT EXISTS user_notes (id BIGSERIAL PRIMARY KEY,user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,title VARCHAR(100) NOT NULL DEFAULT '',body VARCHAR(5000) NOT NULL DEFAULT '',updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()); CREATE INDEX IF NOT EXISTS user_notes_user_updated_idx ON user_notes(user_id,updated_at DESC); ALTER TABLE user_notes ENABLE ROW LEVEL SECURITY; REVOKE ALL ON TABLE user_notes FROM anon,authenticated; CREATE TABLE IF NOT EXISTS support_tickets (id BIGSERIAL PRIMARY KEY,user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,subject VARCHAR(120) NOT NULL,category TEXT NOT NULL DEFAULT 'question' CHECK(category IN ('question','suggestion','problem')),status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','in_progress','closed')),created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()); CREATE INDEX IF NOT EXISTS support_tickets_updated_idx ON support_tickets(updated_at DESC); ALTER TABLE support_tickets ENABLE ROW LEVEL SECURITY; REVOKE ALL ON TABLE support_tickets FROM anon,authenticated; CREATE TABLE IF NOT EXISTS support_messages (id BIGSERIAL PRIMARY KEY,ticket_id BIGINT NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,sender_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,body VARCHAR(4000) NOT NULL CHECK(char_length(body) BETWEEN 1 AND 4000),created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()); CREATE INDEX IF NOT EXISTS support_messages_ticket_idx ON support_messages(ticket_id,created_at); ALTER TABLE messages ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ; ALTER TABLE messages ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ; ALTER TABLE support_messages ENABLE ROW LEVEL SECURITY; REVOKE ALL ON TABLE support_messages FROM anon,authenticated; CREATE TABLE IF NOT EXISTS chat_pins (chat_id BIGINT PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,pinned_by BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,pinned_at TIMESTAMPTZ NOT NULL DEFAULT NOW()); ALTER TABLE chat_pins ENABLE ROW LEVEL SECURITY; REVOKE ALL ON TABLE chat_pins FROM anon,authenticated"));

if (!JWT_SECRET || JWT_SECRET.length < 32) throw new Error("JWT_SECRET must contain at least 32 characters");
app.use(cors({ origin: allowedOrigins }));
app.use(express.json({ limit: "1mb" }));
const uploadDir = path.resolve("uploads");
fs.mkdirSync(uploadDir, { recursive: true });
app.use("/uploads", express.static(uploadDir));

type AuthedRequest = express.Request & { userId?: string };
function signToken(id: string) { return jwt.sign({ sub: id }, JWT_SECRET!, { expiresIn: "7d" }); }
async function auth(req: AuthedRequest, res: express.Response, next: express.NextFunction) {
  const token = req.headers.authorization?.replace(/^Bearer\s+/i, "");
  if (!token) return res.status(401).json({ error: "Требуется вход" });
  try { req.userId = String((jwt.verify(token, JWT_SECRET!) as jwt.JwtPayload).sub); }
  catch { return res.status(401).json({ error: "Сессия истекла" }); }
  try { if (await restriction(String(req.userId),"ban")) return res.status(403).json({error:"Аккаунт заблокирован владельцем DTR"}); next(); }
  catch (e) { next(e); }
}
const onlineUsers = new Set<string>();
const publicUser = (u: any, isOwner = false) => ({ id: String(u.id), username: u.username, displayName: u.display_name, bio: u.bio || "", avatarUrl: u.avatar_url ? "/api/users/" + u.id + "/avatar" : null, age: isOwner || u.age_public ? (u.age ?? null) : null, agePublic: Boolean(u.age_public), role: u.role || "member", statusEmoji: u.status_emoji || null, online: onlineUsers.has(String(u.id)), lastSeenAt: u.last_seen_at || null, theme: u.theme || "dark" });
const staffRoles = new Set(["owner","admin","helper","my_love"]);
async function currentUser(userId: string) { const q=await pool.query("SELECT id,role FROM users WHERE id=$1",[userId]); return q.rows[0] || null; }
async function isStaff(userId: string) { const u=await currentUser(userId); return Boolean(u && staffRoles.has(u.role)); }
async function restriction(userId:string,kind:"ban"|"mute"){const undo=kind==="ban"?"unban":"unmute";const q=await pool.query("SELECT action,expires_at FROM user_moderation WHERE user_id=$1 AND action=ANY($2::text[]) ORDER BY created_at DESC,id DESC LIMIT 1",[userId,[kind,undo]]);if(!q.rowCount||q.rows[0].action!==kind)return null;const until=q.rows[0].expires_at;if(until&&new Date(until).getTime()<=Date.now())return null;return q.rows[0];}
async function canManageChat(chatId:string,userId:string){const q=await pool.query("SELECT c.creator_id,cm.role FROM chats c LEFT JOIN chat_members cm ON cm.chat_id=c.id AND cm.user_id=$2 WHERE c.id=$1",[chatId,userId]);if(!q.rowCount)return false;const u=await currentUser(userId);return String(q.rows[0].creator_id||"")===userId||q.rows[0].role==="admin"||u?.role==="owner"||u?.role==="admin";}

app.get("/api/feed",auth,async(req:AuthedRequest,res)=>{const q=await pool.query("SELECT p.id,p.body,p.created_at,u.id AS author_id,u.username,u.display_name,u.avatar_url,(SELECT COUNT(*)::int FROM feed_likes l WHERE l.post_id=p.id) AS likes,(SELECT COUNT(*)::int FROM feed_comments c WHERE c.post_id=p.id) AS comments,EXISTS(SELECT 1 FROM feed_likes l WHERE l.post_id=p.id AND l.user_id=$1) AS liked FROM feed_posts p JOIN users u ON u.id=p.author_id ORDER BY p.created_at DESC LIMIT 100",[req.userId]);res.json({posts:q.rows.map(p=>({...p,id:String(p.id),authorId:String(p.author_id),avatarUrl:p.avatar_url?"/api/users/"+p.author_id+"/avatar":null,likes:Number(p.likes),comments:Number(p.comments),liked:Boolean(p.liked)}))});});
app.post("/api/feed",auth,async(req:AuthedRequest,res)=>{const body=String(req.body.body||"").trim().slice(0,2000);if(!body)return res.status(400).json({error:"Напиши текст поста"});if(await restriction(String(req.userId),"ban")||await restriction(String(req.userId),"mute"))return res.status(403).json({error:"Публикации временно ограничены"});const q=await pool.query("INSERT INTO feed_posts(author_id,body) VALUES($1,$2) RETURNING id,body,created_at",[req.userId,body]);res.status(201).json({post:{...q.rows[0],id:String(q.rows[0].id),authorId:String(req.userId),likes:0,comments:0,liked:false}});});
app.post("/api/feed/:postId/like",auth,async(req:AuthedRequest,res)=>{const post=await pool.query("SELECT id FROM feed_posts WHERE id=$1",[String(req.params.postId)]);if(!post.rowCount)return res.status(404).json({error:"Пост не найден"});const old=await pool.query("DELETE FROM feed_likes WHERE post_id=$1 AND user_id=$2 RETURNING post_id",[String(req.params.postId),req.userId]);const liked=!old.rowCount;if(liked)await pool.query("INSERT INTO feed_likes(post_id,user_id) VALUES($1,$2) ON CONFLICT DO NOTHING",[String(req.params.postId),req.userId]);const count=await pool.query("SELECT COUNT(*)::int AS count FROM feed_likes WHERE post_id=$1",[String(req.params.postId)]);res.json({liked,likes:count.rows[0].count});});
app.get("/api/feed/:postId/comments",auth,async(req:AuthedRequest,res)=>{const q=await pool.query("SELECT c.id,c.body,c.created_at,u.id AS user_id,u.username,u.display_name,u.avatar_url FROM feed_comments c JOIN users u ON u.id=c.user_id WHERE c.post_id=$1 ORDER BY c.created_at ASC LIMIT 200",[String(req.params.postId)]);res.json({comments:q.rows.map(c=>({...c,id:String(c.id),userId:String(c.user_id),avatarUrl:c.avatar_url?"/api/users/"+c.user_id+"/avatar":null}))});});
app.post("/api/feed/:postId/comments",auth,async(req:AuthedRequest,res)=>{const body=String(req.body.body||"").trim().slice(0,1000);if(!body)return res.status(400).json({error:"Напиши комментарий"});if(await restriction(String(req.userId),"ban")||await restriction(String(req.userId),"mute"))return res.status(403).json({error:"Комментарии временно ограничены"});const q=await pool.query("INSERT INTO feed_comments(post_id,user_id,body) SELECT p.id,$2,$3 FROM feed_posts p WHERE p.id=$1 RETURNING id,post_id,user_id,body,created_at",[String(req.params.postId),req.userId,body]);if(!q.rowCount)return res.status(404).json({error:"Пост не найден"});const u=await pool.query("SELECT username,display_name,avatar_url FROM users WHERE id=$1",[req.userId]);res.status(201).json({comment:{...q.rows[0],id:String(q.rows[0].id),userId:String(q.rows[0].user_id),...u.rows[0],avatarUrl:u.rows[0].avatar_url?"/api/users/"+req.userId+"/avatar":null}});});
app.post("/api/feed/:postId/report",auth,async(req:AuthedRequest,res)=>{const reason=String(req.body.reason||"").trim().slice(0,500);const q=await pool.query("INSERT INTO feed_reports(post_id,user_id,reason) SELECT p.id,$2,$3 FROM feed_posts p WHERE p.id=$1 ON CONFLICT(post_id,user_id) DO NOTHING RETURNING id",[String(req.params.postId),req.userId,reason]);if(!q.rowCount){const exists=await pool.query("SELECT id FROM feed_posts WHERE id=$1",[String(req.params.postId)]);if(!exists.rowCount)return res.status(404).json({error:"Пост не найден"});}res.json({ok:true});});
app.get("/api/stories",auth,async(_req,res)=>{await pool.query("DELETE FROM user_stories WHERE expires_at<=NOW()");const q=await pool.query("SELECT s.id,s.body,s.created_at,s.expires_at,s.media_mime,s.media_name,u.id AS user_id,u.username,u.display_name,u.avatar_url FROM user_stories s JOIN users u ON u.id=s.user_id WHERE s.expires_at>NOW() ORDER BY s.created_at DESC LIMIT 100");res.json({stories:q.rows.map(x=>({...x,id:String(x.id),userId:String(x.user_id),avatarUrl:x.avatar_url?"/api/users/"+x.user_id+"/avatar":null,mediaUrl:x.media_mime?"/api/stories/"+x.id+"/media":null}))});});
const storyMediaUpload=multer({storage:multer.memoryStorage(),limits:{fileSize:12*1024*1024},fileFilter:(_req,file,cb)=>cb(null,["video/mp4","video/webm","video/quicktime","image/png","image/jpeg","image/webp","image/gif"].includes(file.mimetype))});
app.post("/api/stories",auth,storyMediaUpload.single("media"),async(req:AuthedRequest,res)=>{const body=String(req.body.body||"").trim().slice(0,1000);const file=req.file;if(!body&&!file)return res.status(400).json({error:"Добавь текст или видео в историю"});if(await restriction(String(req.userId),"ban")||await restriction(String(req.userId),"mute"))return res.status(403).json({error:"Публикации временно ограничены"});const q=await pool.query("INSERT INTO user_stories(user_id,body,media_data,media_mime,media_name) VALUES($1,$2,$3,$4,$5) RETURNING id,body,created_at,expires_at,media_mime,media_name",[req.userId,body,file?.buffer||null,file?.mimetype||null,file?.originalname||null]);res.status(201).json({story:{...q.rows[0],id:String(q.rows[0].id),userId:String(req.userId),mediaUrl:file?"/api/stories/"+q.rows[0].id+"/media":null}});});
app.get("/api/stories/:storyId/media",auth,async(req,res)=>{const q=await pool.query("SELECT media_data,media_mime,media_name FROM user_stories WHERE id=$1 AND expires_at>NOW()",[String(req.params.storyId)]);if(!q.rowCount||!q.rows[0].media_data)return res.sendStatus(404);res.setHeader("Content-Type",q.rows[0].media_mime||"video/mp4");res.setHeader("Content-Disposition",'inline; filename="'+String(q.rows[0].media_name||"story-video").replace(/[^a-zA-Z0-9._-]/g,"_")+'"');res.setHeader("Cache-Control","private, no-store");res.send(q.rows[0].media_data);});
app.post("/api/messages/:messageId/reactions",auth,async(req:AuthedRequest,res)=>{const emoji=String(req.body.emoji||"").trim().slice(0,16);if(!emoji)return res.status(400).json({error:"Выбери реакцию"});const m=await pool.query("SELECT m.id,m.chat_id FROM messages m JOIN chat_members cm ON cm.chat_id=m.chat_id AND cm.user_id=$2 WHERE m.id=$1",[String(req.params.messageId),req.userId]);if(!m.rowCount)return res.status(404).json({error:"Сообщение не найдено"});const old=await pool.query("DELETE FROM message_reactions WHERE message_id=$1 AND user_id=$2 AND emoji=$3 RETURNING message_id",[String(req.params.messageId),req.userId,emoji]);if(!old.rowCount)await pool.query("INSERT INTO message_reactions(message_id,user_id,emoji) VALUES($1,$2,$3) ON CONFLICT DO NOTHING",[String(req.params.messageId),req.userId,emoji]);const q=await pool.query("SELECT emoji,COUNT(*)::int AS count,BOOL_OR(user_id=$2) AS mine FROM message_reactions WHERE message_id=$1 GROUP BY emoji ORDER BY count DESC",[String(req.params.messageId),req.userId]);res.json({reactions:q.rows});});
app.post("/api/channels",auth,async(req:AuthedRequest,res)=>{const title=String(req.body.title||"").trim().slice(0,60);const description=String(req.body.description||"").trim().slice(0,500);if(!title)return res.status(400).json({error:"Укажи название канала"});if(await restriction(String(req.userId),"ban"))return res.status(403).json({error:"Аккаунт ограничен"});const code=randomBytes(9).toString("base64url");const c=await pool.query("INSERT INTO chats(chat_type,title,description,creator_id,invite_code) VALUES('channel',$1,$2,$3,$4) RETURNING id,title,description,invite_code",[title,description,req.userId,code]);await pool.query("INSERT INTO chat_members(chat_id,user_id,role) VALUES($1,$2,'admin')",[c.rows[0].id,req.userId]);res.status(201).json({channel:{...c.rows[0],id:String(c.rows[0].id),creatorId:String(req.userId)}});});
app.patch("/api/chats/:chatId",auth,async(req:AuthedRequest,res)=>{if(!(await canManageChat(String(req.params.chatId),String(req.userId))))return res.status(403).json({error:"Управлять чатом может создатель или администратор"});const title=String(req.body.title||"").trim().slice(0,60);const description=String(req.body.description||"").trim().slice(0,500);if(!title)return res.status(400).json({error:"Название не может быть пустым"});const q=await pool.query("UPDATE chats SET title=$1,description=$2 WHERE id=$3 AND chat_type IN ('group','channel') RETURNING id,title,description,chat_type,invite_code",[title,description,String(req.params.chatId)]);if(!q.rowCount)return res.status(404).json({error:"Группа или канал не найден"});res.json({chat:{...q.rows[0],id:String(q.rows[0].id)}});});
app.get("/api/chats/:chatId/members",auth,async(req:AuthedRequest,res)=>{const member=await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2",[String(req.params.chatId),req.userId]);if(!member.rowCount)return res.status(403).json({error:"Нет доступа"});const q=await pool.query("SELECT u.id,u.username,u.display_name,u.avatar_url,cm.role,cm.joined_at FROM chat_members cm JOIN users u ON u.id=cm.user_id WHERE cm.chat_id=$1 ORDER BY cm.role DESC,cm.joined_at",[String(req.params.chatId)]);res.json({members:q.rows.map(x=>({...x,id:String(x.id),avatarUrl:x.avatar_url?"/api/users/"+x.id+"/avatar":null}))});});
app.post("/api/chats/:chatId/admins",auth,async(req:AuthedRequest,res)=>{if(!(await canManageChat(String(req.params.chatId),String(req.userId))))return res.status(403).json({error:"Только создатель или администратор может назначать админов"});const userId=String(req.body.userId||"");if(!/^\d+$/.test(userId))return res.status(400).json({error:"Некорректный пользователь"});const q=await pool.query("UPDATE chat_members SET role='admin' WHERE chat_id=$1 AND user_id=$2 RETURNING user_id",[String(req.params.chatId),userId]);if(!q.rowCount)return res.status(404).json({error:"Участник не найден"});res.json({ok:true});});
app.post("/api/chats/join/:inviteCode",auth,async(req:AuthedRequest,res)=>{const c=await pool.query("SELECT id,chat_type FROM chats WHERE invite_code=$1 AND chat_type IN ('group','channel')",[String(req.params.inviteCode)]);if(!c.rowCount)return res.status(404).json({error:"Ссылка недействительна"});await pool.query("INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2) ON CONFLICT DO NOTHING",[c.rows[0].id,req.userId]);res.json({chatId:String(c.rows[0].id),type:c.rows[0].chat_type});});
app.post("/api/admin/users/:userId/moderation",auth,async(req:AuthedRequest,res)=>{const actor=await currentUser(String(req.userId));if(actor?.role!=="owner")return res.status(403).json({error:"Только владелец может применять наказания"});const target=String(String(req.params.userId));const action=String(req.body.action||"");if(!/^\d+$/.test(target)||!["ban","mute","warn","unban","unmute"].includes(action))return res.status(400).json({error:"Некорректное действие"});const reason=String(req.body.reason||"").trim().slice(0,500);const minutes=Math.max(0,Math.min(525600,Number(req.body.minutes)||0));const expires=minutes?new Date(Date.now()+minutes*60000):null;const q=await pool.query("INSERT INTO user_moderation(user_id,action,reason,expires_at,created_by) SELECT id,$2,$3,$4,$5 FROM users WHERE id=$1 RETURNING id,action,reason,expires_at,created_at",[target,action,reason,expires,req.userId]);if(!q.rowCount)return res.status(404).json({error:"Пользователь не найден"});res.status(201).json({moderation:{...q.rows[0],id:String(q.rows[0].id),userId:target}});});

app.get("/api/admin/feed-reports",auth,async(req:AuthedRequest,res)=>{const actor=await currentUser(String(req.userId));if(actor?.role!=="owner")return res.status(403).json({error:"Только владелец"});const q=await pool.query("SELECT r.id,r.post_id,r.user_id,r.reason,r.created_at,p.body,p.author_id,reporter.username AS reporter_username,author.username AS author_username,author.display_name AS author_name FROM feed_reports r JOIN feed_posts p ON p.id=r.post_id JOIN users reporter ON reporter.id=r.user_id JOIN users author ON author.id=p.author_id ORDER BY r.created_at DESC LIMIT 100");res.json({reports:q.rows.map(x=>({...x,id:String(x.id),postId:String(x.post_id),userId:String(x.user_id),authorId:String(x.author_id)}))});});
app.delete("/api/admin/feed-posts/:postId",auth,async(req:AuthedRequest,res)=>{const actor=await currentUser(String(req.userId));if(actor?.role!=="owner")return res.status(403).json({error:"Только владелец может удалить пост"});const q=await pool.query("DELETE FROM feed_posts WHERE id=$1 RETURNING id",[String(req.params.postId)]);if(!q.rowCount)return res.status(404).json({error:"Пост не найден"});res.json({ok:true});});

app.get("/api/admin/moderation",auth,async(req:AuthedRequest,res)=>{const actor=await currentUser(String(req.userId));if(actor?.role!=="owner")return res.status(403).json({error:"Только владелец"});const q=await pool.query("SELECT m.id,m.user_id,m.action,m.reason,m.expires_at,m.created_at,u.username,u.display_name FROM user_moderation m JOIN users u ON u.id=m.user_id ORDER BY m.created_at DESC LIMIT 200");res.json({records:q.rows.map(x=>({...x,id:String(x.id),userId:String(x.user_id)}))});});

app.get("/api/health", (_req, res) => res.json({ ok: true }));

app.get("/api/push/public-key", auth, (_req,res)=>{if(!VAPID_PUBLIC_KEY)return res.status(503).json({error:"Push-уведомления пока не настроены"});res.json({publicKey:VAPID_PUBLIC_KEY});});
app.post("/api/push/subscribe", auth, async (req:AuthedRequest,res)=>{const sub=req.body;let endpointAllowed=false;try{const host=new URL(String(sub?.endpoint||"")).hostname;endpointAllowed=String(sub?.endpoint||"").startsWith("https://")&&(host==="fcm.googleapis.com"||host==="push.services.mozilla.com"||host.endsWith(".push.services.mozilla.com")||host==="web.push.apple.com"||host.endsWith(".push.apple.com")||host.endsWith(".notify.windows.com"));}catch{}if(!endpointAllowed||!sub?.keys?.p256dh||!sub?.keys?.auth)return res.status(400).json({error:"Некорректная push-подписка"});await pool.query("INSERT INTO push_subscriptions(user_id,endpoint,subscription) VALUES($1,$2,$3::jsonb) ON CONFLICT(endpoint) DO UPDATE SET user_id=EXCLUDED.user_id,subscription=EXCLUDED.subscription",[req.userId,sub.endpoint,JSON.stringify(sub)]);res.json({ok:true});});
app.delete("/api/push/subscribe", auth, async (req:AuthedRequest,res)=>{const endpoint=String(req.body.endpoint||"");if(!endpoint.startsWith("https://"))return res.status(400).json({error:"Некорректная подписка"});await pool.query("DELETE FROM push_subscriptions WHERE user_id=$1 AND endpoint=$2",[req.userId,endpoint]);res.json({ok:true});});
async function pushUser(userId:string,payload:{title:string;body:string;url?:string;tag?:string}){if(!VAPID_PUBLIC_KEY||!VAPID_PRIVATE_KEY)return;const q=await pool.query("SELECT endpoint,subscription FROM push_subscriptions WHERE user_id=$1",[userId]);await Promise.all(q.rows.map(async row=>{try{await webpush.sendNotification(row.subscription,JSON.stringify(payload),{TTL:90,urgency:"high"});}catch(error:any){if(error.statusCode===404||error.statusCode===410)await pool.query("DELETE FROM push_subscriptions WHERE endpoint=$1",[row.endpoint]);else console.warn("Push delivery failed:",error.statusCode||error.message);}}));}
async function pushChatMembers(chatId:string,senderId:string,payload:{title:string;body:string;url?:string;tag?:string}){const q=await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1 AND user_id<>$2",[chatId,senderId]);await Promise.all(q.rows.map(row=>pushUser(String(row.user_id),payload)));}


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

app.patch("/api/profile/preferences", auth, async (req:AuthedRequest,res)=>{const theme=req.body.theme==="light"?"light":"dark";const q=await pool.query("UPDATE users SET theme=$1 WHERE id=$2 RETURNING *",[theme,req.userId]);res.json({user:publicUser(q.rows[0],true)});});
app.patch("/api/profile/status", auth, async (req:AuthedRequest,res)=>{const emoji=req.body.statusEmoji===null||req.body.statusEmoji===""?null:String(req.body.statusEmoji||"").slice(0,12);const q=await pool.query("UPDATE users SET status_emoji=$1 WHERE id=$2 RETURNING *",[emoji,req.userId]);res.json({user:publicUser(q.rows[0],true)});});
app.get("/api/admin/users",auth,async(req:AuthedRequest,res)=>{const actor=await currentUser(String(req.userId));if(actor?.role!=="owner")return res.status(403).json({error:"Только владелец DTR"});const q=await pool.query("SELECT id,username,display_name,bio,avatar_url,role,status_emoji,last_seen_at,created_at FROM users ORDER BY created_at DESC,id DESC LIMIT 5000");res.json({users:q.rows.map(u=>({...publicUser(u,true),createdAt:u.created_at}))});});
app.patch("/api/admin/users/:userId/role", auth, async (req:AuthedRequest,res)=>{const target=String(String(req.params.userId));const role=String(req.body.role||"");const allowed=["owner","admin","helper","vip","my_love","member"];if(!/^\d+$/.test(target)||!allowed.includes(role))return res.status(400).json({error:"Некорректная роль или пользователь"});const actor=await currentUser(String(req.userId));const targetUser=await currentUser(target);if(!actor||!targetUser)return res.status(404).json({error:"Пользователь не найден"});if(actor.role==="owner"){if(targetUser.role==="owner"&&role!=="owner")return res.status(403).json({error:"Нельзя снять роль владельца через эту панель"});}else if(actor.role==="admin"){if(!["member","helper","vip"].includes(role)||["owner","admin","my_love"].includes(targetUser.role))return res.status(403).json({error:"Администратор может назначать только участника, хелпера и VIP"});}else return res.status(403).json({error:"Недостаточно прав"});if(role==="owner"&&actor.role!=="owner")return res.status(403).json({error:"Назначить владельца может только владелец"});if(role==="owner"&&targetUser.role!=="owner")return res.status(409).json({error:"Владелец уже назначен"});const q=await pool.query("UPDATE users SET role=$1 WHERE id=$2 RETURNING id,username,display_name,role",[role,target]);if(staffRoles.has(role))io.in("user:"+target).socketsJoin("support:staff");else io.in("user:"+target).socketsLeave("support:staff");io.emit("user:role-updated",{userId:target,role});res.json({user:{id:String(q.rows[0].id),username:q.rows[0].username,displayName:q.rows[0].display_name,role:q.rows[0].role}});});
app.get("/api/notes",auth,async(req:AuthedRequest,res)=>{const q=await pool.query("SELECT id,title,body,created_at,updated_at FROM user_notes WHERE user_id=$1 ORDER BY updated_at DESC",[req.userId]);res.json({notes:q.rows.map(n=>({...n,id:String(n.id)}))});});
app.post("/api/notes",auth,async(req:AuthedRequest,res)=>{const title=String(req.body.title||"").trim().slice(0,100);const body=String(req.body.body||"").trim().slice(0,5000);if(!title&&!body)return res.status(400).json({error:"Добавь текст заметки"});const q=await pool.query("INSERT INTO user_notes(user_id,title,body) VALUES($1,$2,$3) RETURNING id,title,body,created_at,updated_at",[req.userId,title,body]);res.status(201).json({note:{...q.rows[0],id:String(q.rows[0].id)}});});
app.delete("/api/notes/:noteId",auth,async(req:AuthedRequest,res)=>{const q=await pool.query("DELETE FROM user_notes WHERE id=$1 AND user_id=$2",[String(req.params.noteId),req.userId]);if(!q.rowCount)return res.status(404).json({error:"Заметка не найдена"});res.json({ok:true});});
app.get("/api/support/tickets",auth,async(req:AuthedRequest,res)=>{const staff=await isStaff(String(req.userId));const filter=staff?"":"WHERE t.user_id=$1";const args=staff?[]:[req.userId];const q=await pool.query("SELECT t.id,t.user_id,t.subject,t.category,t.status,t.created_at,t.updated_at,u.username,u.display_name,(SELECT body FROM support_messages sm WHERE sm.ticket_id=t.id ORDER BY sm.created_at DESC LIMIT 1) AS last_message FROM support_tickets t JOIN users u ON u.id=t.user_id "+filter+" ORDER BY t.updated_at DESC LIMIT 100",args);res.json({tickets:q.rows.map(t=>({...t,id:String(t.id),userId:String(t.user_id)}))});});
app.post("/api/support/tickets",auth,async(req:AuthedRequest,res)=>{const subject=String(req.body.subject||"").trim().slice(0,120);const body=String(req.body.body||"").trim().slice(0,4000);const category=["question","suggestion","problem"].includes(req.body.category)?req.body.category:"question";if(!subject||!body)return res.status(400).json({error:"Заполни тему и сообщение"});const client=await pool.connect();try{await client.query("BEGIN");const t=await client.query("INSERT INTO support_tickets(user_id,subject,category) VALUES($1,$2,$3) RETURNING *",[req.userId,subject,category]);await client.query("INSERT INTO support_messages(ticket_id,sender_id,body) VALUES($1,$2,$3)",[t.rows[0].id,req.userId,body]);await client.query("COMMIT");const staff=await pool.query("SELECT id FROM users WHERE role=ANY($1::text[])",[["owner","admin","helper","my_love"]]);for(const u of staff.rows){io.to("user:"+u.id).emit("support:refresh",{ticketId:String(t.rows[0].id)});void pushUser(String(u.id),{title:"Новое обращение в DTR",body:subject,url:"/",tag:"support-"+t.rows[0].id});}res.status(201).json({ticket:{...t.rows[0],id:String(t.rows[0].id)}});}catch(e){await client.query("ROLLBACK");console.error(e);res.status(500).json({error:"Не удалось отправить обращение"});}finally{client.release();}});
app.get("/api/support/tickets/:ticketId/messages",auth,async(req:AuthedRequest,res)=>{const staff=await isStaff(String(req.userId));const t=await pool.query("SELECT user_id FROM support_tickets WHERE id=$1",[String(req.params.ticketId)]);if(!t.rowCount)return res.status(404).json({error:"Обращение не найдено"});if(!staff&&String(t.rows[0].user_id)!==String(req.userId))return res.status(403).json({error:"Нет доступа к обращению"});const q=await pool.query("SELECT sm.id,sm.sender_id,sm.body,sm.created_at,u.username,u.display_name FROM support_messages sm JOIN users u ON u.id=sm.sender_id WHERE sm.ticket_id=$1 ORDER BY sm.created_at",[String(req.params.ticketId)]);res.json({messages:q.rows.map(m=>({...m,id:String(m.id),senderId:String(m.sender_id)}))});});
app.post("/api/support/tickets/:ticketId/messages",auth,async(req:AuthedRequest,res)=>{const body=String(req.body.body||"").trim().slice(0,4000);if(!body)return res.status(400).json({error:"Напиши сообщение"});const staff=await isStaff(String(req.userId));const t=await pool.query("SELECT user_id,status FROM support_tickets WHERE id=$1",[String(req.params.ticketId)]);if(!t.rowCount)return res.status(404).json({error:"Обращение не найдено"});if(!staff&&String(t.rows[0].user_id)!==String(req.userId))return res.status(403).json({error:"Нет доступа к обращению"});const q=await pool.query("INSERT INTO support_messages(ticket_id,sender_id,body) VALUES($1,$2,$3) RETURNING *",[String(req.params.ticketId),req.userId,body]);await pool.query("UPDATE support_tickets SET updated_at=NOW(),status=CASE WHEN $2 THEN 'in_progress' ELSE status END WHERE id=$1",[String(req.params.ticketId),staff]);const senderInfo=await pool.query("SELECT username,display_name FROM users WHERE id=$1",[req.userId]);const supportEvent={ticketId:String(String(req.params.ticketId)),message:{id:String(q.rows[0].id),senderId:String(q.rows[0].sender_id),body:q.rows[0].body,created_at:q.rows[0].created_at,username:senderInfo.rows[0]?.username,display_name:senderInfo.rows[0]?.display_name}};if(staff){const recipient=String(t.rows[0].user_id);io.to("user:"+recipient).emit("support:refresh",{ticketId:String(String(req.params.ticketId))});io.to("user:"+recipient).emit("support:message",supportEvent);io.to("support:staff").emit("support:refresh",{ticketId:String(String(req.params.ticketId))});io.to("support:staff").emit("support:message",supportEvent);void pushUser(recipient,{title:"Ответ поддержки DTR",body:body.slice(0,120),url:"/",tag:"support-"+String(req.params.ticketId)});}else{io.to("support:staff").emit("support:refresh",{ticketId:String(String(req.params.ticketId))});io.to("support:staff").emit("support:message",supportEvent);}res.status(201).json({message:{...q.rows[0],id:String(q.rows[0].id),senderId:String(q.rows[0].sender_id)}});});
app.patch("/api/support/tickets/:ticketId/status",auth,async(req:AuthedRequest,res)=>{if(!(await isStaff(String(req.userId))))return res.status(403).json({error:"Недостаточно прав"});const status=["open","in_progress","closed"].includes(req.body.status)?req.body.status:"open";const q=await pool.query("UPDATE support_tickets SET status=$1,updated_at=NOW() WHERE id=$2 RETURNING id,status",[status,String(req.params.ticketId)]);if(!q.rowCount)return res.status(404).json({error:"Обращение не найдено"});res.json({ok:true,status:q.rows[0].status});});

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
const mediaUpload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 12 * 1024 * 1024 }, fileFilter: (_req, file, cb) => cb(null, ["image/png","image/jpeg","image/webp","image/gif","audio/webm","audio/ogg","audio/mp4","audio/mpeg","audio/wav","audio/x-wav","audio/mp4a-latm","video/mp4","video/webm","video/quicktime"].includes(file.mimetype.split(";")[0])) });
app.post("/api/profile/avatar", auth, avatarUpload.single("avatar"), async (req: AuthedRequest, res) => {
  if (!req.file) return res.status(400).json({ error: "Выбери PNG, JPG, WEBP или GIF до 5 МБ" });
  const q = await pool.query("UPDATE users SET avatar_url=$1,avatar_data=$2,avatar_mime=$3 WHERE id=$4 RETURNING *", [`db:${Date.now()}`, req.file.buffer, req.file.mimetype, req.userId]);
  res.json({ user: publicUser(q.rows[0], true) });
});
app.get("/api/users/:userId/avatar", async (req, res) => {
  if (!/^\\d+$/.test(String(req.params.userId))) return res.sendStatus(404);
  try {
    const q = await pool.query("SELECT avatar_url,avatar_data,avatar_mime FROM users WHERE id=$1", [String(req.params.userId)]);
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
  const q = await pool.query("SELECT id,username,display_name,bio,avatar_url,role,status_emoji,last_seen_at FROM users WHERE id<>$1 AND username ILIKE $2 ORDER BY username LIMIT 20", [req.userId, `%${term}%`]);
  res.json({ users: q.rows.map((u: any) => publicUser(u)) });
});
// Chat list query sorts by the latest message timestamp without relying on a SELECT alias.
app.get("/api/chats", auth, async (req: AuthedRequest, res) => {
  try {
    const q = await pool.query(`SELECT c.id,c.chat_type,c.title,c.created_at,c.creator_id,c.description,c.invite_code,(c.avatar_data IS NOT NULL) AS has_avatar,
      (SELECT body FROM messages WHERE chat_id=c.id ORDER BY created_at DESC LIMIT 1) AS last_message,
      (SELECT created_at FROM messages WHERE chat_id=c.id ORDER BY created_at DESC LIMIT 1) AS last_message_at,
      (SELECT json_agg(json_build_object('id',u.id,'username',u.username,'display_name',u.display_name,'bio',u.bio,'avatar_url',u.avatar_url,'age',u.age,'age_public',u.age_public,'role',u.role,'status_emoji',u.status_emoji,'last_seen_at',u.last_seen_at,'member_role',cm.role) ORDER BY u.display_name) FROM chat_members cm JOIN users u ON u.id=cm.user_id WHERE cm.chat_id=c.id) AS members
      FROM chats c JOIN chat_members mine ON mine.chat_id=c.id AND mine.user_id=$1
      ORDER BY COALESCE((SELECT MAX(created_at) FROM messages WHERE chat_id=c.id),c.created_at) DESC`, [req.userId]);
    res.json({ chats: q.rows.map(r => {
      const members = (r.members || []).map((u: any) => ({...publicUser(u, String(u.id) === String(req.userId)),chatRole:u.member_role||"member"}));
      const other = members.find((u: any) => u.id !== String(req.userId));
      return { chatId:String(r.id), type:r.chat_type || "direct", title:r.title, description:r.description||"", inviteCode:r.invite_code||null, creatorId:r.creator_id?String(r.creator_id):null, avatarUrl:r.has_avatar?"/api/chats/"+r.id+"/avatar":null, user:other || null, members, lastMessage:r.last_message, updatedAt:r.last_message_at || r.created_at };
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
    const created = await client.query("INSERT INTO chats(chat_type,title,creator_id) VALUES('group',$1,$2) RETURNING id",[title,req.userId]);
    const chatId = created.rows[0].id;
    await client.query("INSERT INTO chat_members(chat_id,user_id,role) VALUES($1,$2,'admin')",[chatId,req.userId]);
    for (const id of userIds) await client.query("INSERT INTO chat_members(chat_id,user_id,role) VALUES($1,$2,'member')",[chatId,id]);
    await client.query("COMMIT");
    for (const id of [String(req.userId),...userIds]) io.to("user:"+id).emit("chat:refresh",{chatId:String(chatId)});
    res.status(201).json({chatId:String(chatId)});
  } catch(e) { await client.query("ROLLBACK"); console.error("Create group failed:",e); res.status(500).json({error:"Не удалось создать группу"}); }
  finally { client.release(); }
});
app.post("/api/chats/:chatId/members", auth, async (req: AuthedRequest, res) => {
  const userId = String(req.body.userId || "");
  if (!/^[0-9]+$/.test(userId)) return res.status(400).json({error:"Некорректный пользователь"});
  const membership = await pool.query("SELECT c.chat_type FROM chats c JOIN chat_members cm ON cm.chat_id=c.id WHERE c.id=$1 AND cm.user_id=$2",[String(req.params.chatId),req.userId]);
  if (!membership.rowCount) return res.status(403).json({error:"Нет доступа к группе"});
  if (membership.rows[0].chat_type !== "group") return res.status(400).json({error:"Добавлять участников можно только в группу"});
  if (!(await canManageChat(String(req.params.chatId),String(req.userId)))) return res.status(403).json({error:"Добавлять участников может создатель или администратор"});
  const count = await pool.query("SELECT COUNT(*)::int AS count FROM chat_members WHERE chat_id=$1",[String(req.params.chatId)]);
  if (count.rows[0].count >= 20) return res.status(400).json({error:"В группе максимум 20 участников"});
  const user = await pool.query("SELECT id FROM users WHERE id=$1",[userId]);
  if (!user.rowCount) return res.status(404).json({error:"Пользователь не найден"});
  await pool.query("INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2) ON CONFLICT DO NOTHING",[String(req.params.chatId),userId]);
  const members = await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1",[String(req.params.chatId)]);
  for (const m of members.rows) io.to("user:"+m.user_id).emit("chat:refresh",{chatId:String(String(req.params.chatId))});
  res.json({ok:true});
});
app.get("/api/users/:userId/profile", auth, async (req: AuthedRequest, res) => {
  const targetId = String(String(req.params.userId));
  if (!/^[0-9]+$/.test(targetId)) return res.sendStatus(404);
  const q = await pool.query("SELECT id,username,display_name,bio,avatar_url,age,age_public,role,status_emoji,last_seen_at,theme FROM users WHERE id=$1",[targetId]);
  if (!q.rowCount) return res.status(404).json({error:"Пользователь не найден"});
  res.json({user:publicUser(q.rows[0],String(req.userId)===targetId)});
});

const chatAvatarUpload=multer({storage:multer.memoryStorage(),limits:{fileSize:3*1024*1024},fileFilter:(_req,file,cb)=>/^image\/(png|jpeg|webp|gif)$/.test(file.mimetype)?cb(null,true):cb(new Error("Нужен файл изображения"))});
app.post("/api/chats/:chatId/avatar",auth,chatAvatarUpload.single("avatar"),async(req:AuthedRequest,res)=>{if(!(await canManageChat(String(req.params.chatId),String(req.userId))))return res.status(403).json({error:"Изменять аватар может создатель или администратор"});if(!req.file)return res.status(400).json({error:"Выбери изображение"});const q=await pool.query("UPDATE chats SET avatar_data=$1,avatar_mime=$2 WHERE id=$3 AND chat_type IN ('group','channel') RETURNING id",[req.file.buffer,req.file.mimetype,String(req.params.chatId)]);if(!q.rowCount)return res.status(404).json({error:"Группа или канал не найден"});res.json({avatarUrl:"/api/chats/"+String(req.params.chatId)+"/avatar"});});
app.get("/api/chats/:chatId/avatar",async(req,res)=>{const q=await pool.query("SELECT avatar_data,avatar_mime FROM chats WHERE id=$1",[String(req.params.chatId)]);if(!q.rowCount||!q.rows[0].avatar_data)return res.sendStatus(404);res.setHeader("Content-Type",q.rows[0].avatar_mime||"image/jpeg");res.setHeader("Cache-Control","public, max-age=300");res.send(q.rows[0].avatar_data);});


const stickerUpload=multer({storage:multer.memoryStorage(),limits:{fileSize:2*1024*1024},fileFilter:(_req,file,cb)=>/^image\/(png|webp|gif)$/.test(file.mimetype)?cb(null,true):cb(new Error("Для стикера используй PNG, WebP или GIF"))});
app.get("/api/stickers",auth,async(req:AuthedRequest,res)=>{const q=await pool.query("SELECT id,name,mime,created_at FROM user_stickers WHERE user_id=$1 ORDER BY created_at DESC LIMIT 100",[req.userId]);res.json({stickers:q.rows.map(x=>({...x,id:String(x.id)}))});});
app.post("/api/stickers",auth,stickerUpload.single("sticker"),async(req:AuthedRequest,res)=>{if(!req.file)return res.status(400).json({error:"Выбери изображение-стикер"});const name=String(req.body.name||"Мой стикер").trim().slice(0,60)||"Мой стикер";const q=await pool.query("INSERT INTO user_stickers(user_id,name,mime,data) VALUES($1,$2,$3,$4) RETURNING id,name,mime,created_at",[req.userId,name,req.file.mimetype,req.file.buffer]);res.status(201).json({sticker:{...q.rows[0],id:String(q.rows[0].id)}});});
app.get("/api/stickers/:stickerId/file",auth,async(req:AuthedRequest,res)=>{const q=await pool.query("SELECT mime,data FROM user_stickers WHERE id=$1 AND user_id=$2",[String(req.params.stickerId),req.userId]);if(!q.rowCount)return res.sendStatus(404);res.setHeader("Content-Type",q.rows[0].mime);res.setHeader("Cache-Control","private, max-age=60");res.send(q.rows[0].data);});
app.delete("/api/stickers/:stickerId",auth,async(req:AuthedRequest,res)=>{const q=await pool.query("DELETE FROM user_stickers WHERE id=$1 AND user_id=$2 RETURNING id",[String(req.params.stickerId),req.userId]);if(!q.rowCount)return res.sendStatus(404);res.json({ok:true});});

app.get("/api/chats/:chatId/pin",auth,async(req:AuthedRequest,res)=>{const chatId=String(req.params.chatId);const member=await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2",[chatId,req.userId]);if(!member.rowCount)return res.status(403).json({error:"Нет доступа к чату"});const q=await pool.query("SELECT m.id,m.body,m.message_type,m.created_at,m.sender_id,u.display_name FROM chat_pins p JOIN messages m ON m.id=p.message_id JOIN users u ON u.id=m.sender_id WHERE p.chat_id=$1",[chatId]);const m=q.rows[0];res.json({pinned:m?{id:String(m.id),body:m.body,messageType:m.message_type,createdAt:m.created_at,senderId:String(m.sender_id),displayName:m.display_name}:null});});
app.post("/api/chats/:chatId/pin/:messageId",auth,async(req:AuthedRequest,res)=>{const chatId=String(req.params.chatId),messageId=String(req.params.messageId);const member=await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2",[chatId,req.userId]);if(!member.rowCount)return res.status(403).json({error:"Нет доступа к чату"});const msg=await pool.query("SELECT id,body,message_type,created_at,sender_id FROM messages WHERE id=$1 AND chat_id=$2 AND deleted_at IS NULL",[messageId,chatId]);if(!msg.rowCount)return res.status(404).json({error:"Сообщение не найдено"});await pool.query("INSERT INTO chat_pins(chat_id,message_id,pinned_by) VALUES($1,$2,$3) ON CONFLICT(chat_id) DO UPDATE SET message_id=EXCLUDED.message_id,pinned_by=EXCLUDED.pinned_by,pinned_at=NOW()",[chatId,messageId,req.userId]);const m=msg.rows[0];res.json({pinned:{id:String(m.id),body:m.body,messageType:m.message_type,createdAt:m.created_at,senderId:String(m.sender_id)}});});
app.delete("/api/chats/:chatId/pin",auth,async(req:AuthedRequest,res)=>{const chatId=String(req.params.chatId);const member=await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2",[chatId,req.userId]);if(!member.rowCount)return res.status(403).json({error:"Нет доступа к чату"});await pool.query("DELETE FROM chat_pins WHERE chat_id=$1",[chatId]);res.json({pinned:null});});
app.get("/api/chats/:chatId/messages", auth, async (req: AuthedRequest, res) => {
  const member = await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2", [String(req.params.chatId), req.userId]);
  if (!member.rowCount) return res.status(403).json({ error: "Нет доступа к чату" });
  const q = await pool.query(`SELECT m.id,m.body,m.message_type,m.created_at,m.sender_id,m.edited_at,u.username,u.display_name,(SELECT json_agg(json_build_object('emoji',r.emoji,'count',r.count,'mine',r.mine)) FROM (SELECT emoji,COUNT(*)::int AS count,BOOL_OR(user_id=$2) AS mine FROM message_reactions WHERE message_id=m.id GROUP BY emoji) r) AS reactions FROM messages m JOIN users u ON u.id=m.sender_id WHERE m.chat_id=$1 ORDER BY m.created_at ASC LIMIT 200`, [String(req.params.chatId),req.userId]);
  await pool.query("UPDATE chat_members SET last_read_at=NOW() WHERE chat_id=$1 AND user_id=$2", [String(req.params.chatId), req.userId]);
  res.json({ messages: q.rows.map(m => ({ id:String(m.id), body:m.body, messageType:m.message_type, mediaUrl:m.message_type === "text" ? null : `/api/messages/${m.id}/media`, createdAt:m.created_at, senderId:String(m.sender_id), username:m.username, displayName:m.display_name, editedAt:m.edited_at||null, reactions:m.reactions||[] })) });
});
app.post("/api/chats/:chatId/messages", auth, async (req: AuthedRequest, res) => {
  const body = String(req.body.body || "").trim();
  if (!body || body.length > 4000) return res.status(400).json({ error: "Сообщение должно быть от 1 до 4000 символов" });
  const member = await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2", [String(req.params.chatId), req.userId]);
  if (!member.rowCount) return res.status(403).json({ error: "Нет доступа к чату" });
  if (await restriction(String(req.userId),"ban")) return res.status(403).json({error:"Аккаунт заблокирован"});
  if (await restriction(String(req.userId),"mute")) return res.status(403).json({error:"Вам временно запрещено отправлять сообщения"});
  const q = await pool.query("INSERT INTO messages(chat_id,sender_id,body) VALUES($1,$2,$3) RETURNING *", [String(req.params.chatId), req.userId, body]);
  const sender = await pool.query("SELECT username,display_name FROM users WHERE id=$1",[req.userId]);
  const message = { id:String(q.rows[0].id), chatId:String(q.rows[0].chat_id), senderId:String(q.rows[0].sender_id), body:q.rows[0].body, messageType:"text", mediaUrl:null, createdAt:q.rows[0].created_at, username:sender.rows[0]?.username, displayName:sender.rows[0]?.display_name };
  const members = await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1", [String(req.params.chatId)]);
  for (const m of members.rows) io.to(`user:${m.user_id}`).emit("chat:message", message);
  void pushChatMembers(String(String(req.params.chatId)),String(req.userId),{title:sender.rows[0]?.display_name||"Новое сообщение",body:body.slice(0,140),url:"/",tag:"chat-"+String(req.params.chatId)});
  res.status(201).json({ message });
});
app.post("/api/chats/:chatId/forward/:messageId", auth, async (req: AuthedRequest, res) => {
  const chatId=String(req.params.chatId), messageId=String(req.params.messageId);
  if(await restriction(String(req.userId),"ban"))return res.status(403).json({error:"Аккаунт заблокирован"});
  if(await restriction(String(req.userId),"mute"))return res.status(403).json({error:"Вам временно запрещено отправлять сообщения"});
  const access=await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2",[chatId,req.userId]);
  if(!access.rowCount)return res.status(403).json({error:"Нет доступа к чату"});
  const source=await pool.query("SELECT m.id,m.body,m.message_type,u.display_name,mm.mime_type,mm.file_name,mm.file_size,mm.data FROM messages m JOIN chat_members cm ON cm.chat_id=m.chat_id AND cm.user_id=$2 JOIN users u ON u.id=m.sender_id LEFT JOIN message_media mm ON mm.message_id=m.id WHERE m.id=$1 AND m.message_type IN ('text','image','video','audio') AND COALESCE(m.deleted_at IS NULL,true)",[messageId,req.userId]);
  if(!source.rowCount)return res.status(404).json({error:"Исходное сообщение не найдено"});
  const src=source.rows[0];
  const forwardedBody=("↪ Переслано"+(src.display_name?" от "+src.display_name:"")+"\n"+String(src.body||"")).slice(0,4000);
  const client=await pool.connect();
  let inserted:any;
  try{
    await client.query("BEGIN");
    const q=await client.query("INSERT INTO messages(chat_id,sender_id,body,message_type) VALUES($1,$2,$3,$4) RETURNING id,chat_id,body,message_type,created_at",[chatId,req.userId,forwardedBody,src.message_type]);
    inserted=q.rows[0];
    if(src.data)await client.query("INSERT INTO message_media(message_id,mime_type,file_name,file_size,data) VALUES($1,$2,$3,$4,$5)",[inserted.id,src.mime_type,src.file_name,src.file_size,src.data]);
    await client.query("COMMIT");
  }catch(err){await client.query("ROLLBACK");throw err;}finally{client.release();}
  const sender=await pool.query("SELECT username,display_name FROM users WHERE id=$1",[req.userId]);
  const message={id:String(inserted.id),chatId,senderId:String(req.userId),body:inserted.body,messageType:inserted.message_type,mediaUrl:inserted.message_type==="text"?null:`/api/messages/${inserted.id}/media`,createdAt:inserted.created_at,username:sender.rows[0]?.username,displayName:sender.rows[0]?.display_name};
  const members=await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1",[chatId]);
  for(const m of members.rows)io.to(`user:${m.user_id}`).emit("chat:message",message);
  void pushChatMembers(chatId,String(req.userId),{title:sender.rows[0]?.display_name||"Пересланное сообщение",body:src.message_type==="image"?"Переслал(а) фото":src.message_type==="video"?"Переслал(а) видео":src.message_type==="audio"?"Переслал(а) голосовое сообщение":forwardedBody.slice(0,140),url:"/",tag:"chat-"+chatId});
  res.status(201).json({message});
});
app.patch("/api/messages/:messageId",auth,async(req:AuthedRequest,res)=>{const body=String(req.body.body||"").trim();if(!body||body.length>4000)return res.status(400).json({error:"Сообщение должно быть от 1 до 4000 символов"});const q=await pool.query("UPDATE messages m SET body=$3,edited_at=NOW() FROM chat_members cm WHERE m.id=$1 AND cm.chat_id=m.chat_id AND cm.user_id=$2 AND m.sender_id=$2 AND m.message_type='text' AND m.created_at>NOW()-INTERVAL '24 hours' RETURNING m.id,m.chat_id,m.body,m.edited_at",[String(req.params.messageId),req.userId,body]);if(!q.rowCount)return res.status(404).json({error:"Сообщение не найдено или уже нельзя изменить"});const m=q.rows[0];const members=await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1",[String(m.chat_id)]);for(const x of members.rows)io.to("user:"+x.user_id).emit("chat:message-edited",{id:String(m.id),chatId:String(m.chat_id),body:m.body,editedAt:m.edited_at});res.json({message:{id:String(m.id),body:m.body,editedAt:m.edited_at}});});
app.delete("/api/messages/:messageId",auth,async(req:AuthedRequest,res)=>{const q=await pool.query("UPDATE messages m SET body='Сообщение удалено',deleted_at=NOW(),edited_at=NULL WHERE m.id=$1 AND m.sender_id=$2 AND m.message_type='text' AND m.deleted_at IS NULL RETURNING m.id,m.chat_id,m.body",[String(req.params.messageId),req.userId]);if(!q.rowCount)return res.status(404).json({error:"Сообщение не найдено или уже удалено"});const m=q.rows[0];const members=await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1",[String(m.chat_id)]);for(const x of members.rows)io.to("user:"+x.user_id).emit("chat:message-edited",{id:String(m.id),chatId:String(m.chat_id),body:m.body,deleted:true});res.json({ok:true});});
app.post("/api/chats/:chatId/media", auth, mediaUpload.single("media"), async (req: AuthedRequest, res) => {
  const file = req.file;
  if (!file) return res.status(400).json({ error: "Выбери фото или запиши голосовое сообщение в поддерживаемом формате" });
  const isImage = file.mimetype.startsWith("image/"); const isAudio = file.mimetype.startsWith("audio/"); const isVideo=file.mimetype.startsWith("video/");
  if (!isImage && !isAudio && !isVideo) return res.status(415).json({ error: "Поддерживаются изображения, видео и аудио" });
  const member = await pool.query("SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2", [String(req.params.chatId), req.userId]);
  if (!member.rowCount) return res.status(403).json({ error: "Нет доступа к чату" });
  const caption = String(req.body.caption || "").trim().slice(0, 500); const body = caption || (isImage ? "Фото" : isVideo ? "Видео" : "Голосовое сообщение");
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const inserted = await client.query("INSERT INTO messages(chat_id,sender_id,body,message_type) VALUES($1,$2,$3,$4) RETURNING *", [String(req.params.chatId), req.userId, body, isImage ? "image" : isVideo ? "video" : "audio"]);
    const messageId = inserted.rows[0].id;
    const safeName = path.basename(file.originalname).replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 120) || (isImage ? "image" : "voice");
    await client.query("INSERT INTO message_media(message_id,mime_type,file_name,file_size,data) VALUES($1,$2,$3,$4,$5)", [messageId, file.mimetype, safeName, file.size, file.buffer]);
    await client.query("COMMIT");
    const sender = await pool.query("SELECT username,display_name FROM users WHERE id=$1",[req.userId]);
    const message = { id:String(messageId), chatId:String(String(req.params.chatId)), senderId:String(req.userId), body, messageType:isImage ? "image" : isVideo ? "video" : "audio", mediaUrl:`/api/messages/${messageId}/media`, createdAt:inserted.rows[0].created_at, username:sender.rows[0]?.username, displayName:sender.rows[0]?.display_name };
    const members = await pool.query("SELECT user_id FROM chat_members WHERE chat_id=$1", [String(req.params.chatId)]);
    for (const m of members.rows) io.to(`user:${m.user_id}`).emit("chat:message", message);
    void pushChatMembers(String(String(req.params.chatId)),String(req.userId),{title:sender.rows[0]?.display_name||"Новое сообщение",body:isImage?"Отправил(а) фото":isVideo?"Отправил(а) видео":"Отправил(а) голосовое сообщение",url:"/",tag:"chat-"+String(req.params.chatId)});
    res.status(201).json({ message });
  } catch (e) { await client.query("ROLLBACK"); console.error("POST /api/chats/:chatId/media failed:", e); res.status(500).json({ error: "Не удалось отправить файл" }); }
  finally { client.release(); }
});
app.get("/api/messages/:messageId/media", auth, async (req: AuthedRequest, res) => {
  const q = await pool.query(`SELECT mm.mime_type,mm.file_name,mm.file_size,mm.data FROM message_media mm JOIN messages m ON m.id=mm.message_id JOIN chat_members cm ON cm.chat_id=m.chat_id AND cm.user_id=$2 WHERE mm.message_id=$1`, [String(req.params.messageId), req.userId]);
  if (!q.rowCount) return res.status(404).json({ error: "Файл не найден или нет доступа" });
  const media = q.rows[0]; res.setHeader("Content-Type", media.mime_type); res.setHeader("Content-Length", String(media.file_size));
  res.setHeader("Content-Disposition", `inline; filename="${media.file_name}"`); res.setHeader("Cache-Control", "private, no-store"); res.setHeader("X-Content-Type-Options", "nosniff"); res.send(media.data);
});
io.use((socket, next) => {
  try { const token = socket.handshake.auth?.token; const payload = jwt.verify(token, JWT_SECRET!) as jwt.JwtPayload; socket.data.userId = String(payload.sub); next(); }
  catch { next(new Error("unauthorized")); }
});
io.on("connection", socket => {
  const userId=String(socket.data.userId);socket.join(`user:${userId}`);
  void (async()=>{try{const u=await currentUser(userId);if(u&&staffRoles.has(u.role))socket.join("support:staff");onlineUsers.add(userId);await pool.query("UPDATE users SET last_seen_at=NULL WHERE id=$1",[userId]);io.emit("presence:update",{userId,online:true,lastSeenAt:null});}catch(e){console.warn("Presence init failed",e);}})();
  socket.on("disconnect",()=>{void (async()=>{try{const remaining=await io.in("user:"+userId).allSockets();if(remaining.size===0){onlineUsers.delete(userId);const q=await pool.query("UPDATE users SET last_seen_at=NOW() WHERE id=$1 RETURNING last_seen_at",[userId]);io.emit("presence:update",{userId,online:false,lastSeenAt:q.rows[0]?.last_seen_at||new Date().toISOString()});}}catch(e){console.warn("Presence update failed",e);}})();});
  const relay=async(payload:any,event:string)=>{const to=String(payload?.to||"");const chatId=String(payload?.chatId||"");if(!/^\d+$/.test(to)||!chatId||to===userId)return;try{const allowed=await pool.query("SELECT 1 FROM chat_members a JOIN chat_members b ON a.chat_id=b.chat_id WHERE a.chat_id=$1 AND a.user_id=$2 AND b.user_id=$3",[chatId,userId,to]);if(!allowed.rowCount)return;const {to:_to,...rest}=payload;io.to(`user:${to}`).emit(event,{...rest,from:userId});}catch(error){console.error("Call signaling failed:",error);}};
  socket.on("call:offer",payload=>void relay(payload,"call:incoming"));
  socket.on("call:answer",payload=>void relay(payload,"call:answered"));
  socket.on("call:ice",payload=>void relay(payload,"call:ice-candidate"));
  socket.on("call:end",payload=>void relay(payload,"call:ended"));
});
// In production, serve the Vite build from the same origin as the API and Socket.IO.
const clientDist = path.resolve("dist");
if (fs.existsSync(clientDist)) {
  app.use(express.static(clientDist));
  app.get("*", (_req, res) => res.sendFile(path.join(clientDist, "index.html")));
}

const port = Number(process.env.PORT || 3001);
Promise.all([schemaReady,featureSchemaReady]).then(() => httpServer.listen(port, () => console.log(`дтр API listening on :${port}`))).catch(error => { console.error("Database schema initialization failed:", error); process.exit(1); });
