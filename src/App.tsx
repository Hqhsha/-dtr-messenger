import { useEffect, useMemo, useState } from "react";
import { io, Socket } from "socket.io-client";

type User = { id:string; username:string; displayName:string; bio:string; avatarUrl:string|null };
type Chat = { chatId:string; user:User; lastMessage:string|null };
type Message = { id:string; chatId:string; senderId:string; body:string; createdAt:string; username?:string; displayName?:string };
const api = async (path:string, token:string|null, options:RequestInit={}) => {
  const headers = new Headers(options.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (options.body && !(options.body instanceof FormData)) headers.set("Content-Type","application/json");
  const response = await fetch(`/api${path}`, { ...options, headers });
  const data = await response.json().catch(()=>({}));
  if (!response.ok) throw new Error(data.error || "Ошибка запроса");
  return data;
};
export default function App() {
  const [token,setToken] = useState(localStorage.getItem("dtr_token"));
  const [me,setMe] = useState<User|null>(null);
  const [mode,setMode] = useState<"login"|"register">("login");
  const [username,setUsername] = useState("");
  const [password,setPassword] = useState("");
  const [displayName,setDisplayName] = useState("");
  const [error,setError] = useState("");
  const [chats,setChats] = useState<Chat[]>([]);
  const [active,setActive] = useState<Chat|null>(null);
  const [messages,setMessages] = useState<Message[]>([]);
  const [search,setSearch] = useState("");
  const [results,setResults] = useState<User[]>([]);
  const [draft,setDraft] = useState("");
  const [profileOpen,setProfileOpen] = useState(false);
  const [bio,setBio] = useState("");
  const [busy,setBusy] = useState(false);
  const socket = useMemo<Socket|null>(()=> token ? io({auth:{token}}) : null,[token]);
  useEffect(()=>{ if(!token)return; api("/me",token).then(d=>{setMe(d.user);setBio(d.user.bio)}).catch(()=>logout()); api("/chats",token).then(d=>setChats(d.chats)).catch(e=>setError(e.message)); return ()=>{socket?.disconnect()}; },[token,socket]);
  useEffect(()=>{ if(!socket)return; const receive=(m:Message)=>{ if(active?.chatId===m.chatId) setMessages(old=>old.some(x=>x.id===m.id)?old:[...old,m]); api("/chats",token).then(d=>setChats(d.chats)).catch(()=>{}); }; socket.on("chat:message",receive); return ()=>{socket.off("chat:message",receive)}; },[socket,active,token]);
  useEffect(()=>{ if(!token || search.trim().length<2){setResults([]);return;} const t=setTimeout(()=>api(`/users/search?q=${encodeURIComponent(search)}`,token).then(d=>setResults(d.users)).catch(e=>setError(e.message)),250); return ()=>clearTimeout(t); },[search,token]);
  function logout(){localStorage.removeItem("dtr_token");setToken(null);setMe(null);setChats([]);setActive(null);setMessages([]);socket?.disconnect();}
  async function submitAuth(e:React.FormEvent){e.preventDefault();setError("");setBusy(true);try{const d=await api(`/auth/${mode}`,null,{method:"POST",body:JSON.stringify({username,password,displayName})});localStorage.setItem("dtr_token",d.token);setToken(d.token);setMe(d.user);setBio(d.user.bio||"");}catch(e:any){setError(e.message)}finally{setBusy(false)}}
  async function openChat(user:User){try{const d=await api("/chats/open",token,{method:"POST",body:JSON.stringify({userId:user.id})});const chat={chatId:d.chatId,user,lastMessage:null};setActive(chat);setSearch("");setResults([]);const m=await api(`/chats/${d.chatId}/messages`,token);setMessages(m.messages);const c=await api("/chats",token);setChats(c.chats);}catch(e:any){setError(e.message)}}
  async function selectChat(chat:Chat){setActive(chat);try{const d=await api(`/chats/${chat.chatId}/messages`,token);setMessages(d.messages)}catch(e:any){setError(e.message)}}
  async function send(e:React.FormEvent){e.preventDefault();if(!draft.trim()||!active)return;const body=draft.trim();setDraft("");try{const d=await api(`/chats/${active.chatId}/messages`,token,{method:"POST",body:JSON.stringify({body})});setMessages(old=>old.some(x=>x.id===d.message.id)?old:[...old,d.message]);const c=await api("/chats",token);setChats(c.chats);}catch(e:any){setError(e.message);setDraft(body)}}
  async function saveProfile(){try{const d=await api("/profile",token,{method:"PATCH",body:JSON.stringify({displayName:me?.displayName,bio})});setMe(d.user);setProfileOpen(false)}catch(e:any){setError(e.message)}}
  async function uploadAvatar(file?:File){if(!file)return;const data=new FormData();data.append("avatar",file);try{const d=await api("/profile/avatar",token,{method:"POST",body:data});setMe(d.user)}catch(e:any){setError(e.message)}}
  if(!token || !me) return <main className="auth"><form className="auth-card" onSubmit={submitAuth}><div className="brand-mark">д</div><h1>дтр</h1><p>Твои разговоры. В одном месте.</p>{mode==="register"&&<input placeholder="Отображаемое имя" value={displayName} onChange={e=>setDisplayName(e.target.value)} maxLength={40}/>}<input placeholder="Логин" value={username} onChange={e=>setUsername(e.target.value)} minLength={3} maxLength={24} required/><input placeholder="Пароль (минимум 8 символов)" type="password" value={password} onChange={e=>setPassword(e.target.value)} minLength={8} required/><button disabled={busy}>{busy?"Подождите…":mode==="login"?"Войти":"Создать аккаунт"}</button><button type="button" className="link" onClick={()=>setMode(mode==="login"?"register":"login")}>{mode==="login"?"Нет аккаунта? Регистрация":"Уже есть аккаунт? Войти"}</button>{error&&<div className="error">{error}</div>}</form></main>;
  return <main className="shell"><aside className="sidebar"><header className="side-head"><div className="brand-mark small">д</div><strong>дтр</strong><button className="icon-btn" onClick={()=>setProfileOpen(!profileOpen)} title="Профиль">⚙</button></header><button className="my-profile" onClick={()=>setProfileOpen(!profileOpen)}><Avatar user={me}/><span><b>{me.displayName}</b><small>@{me.username}</small></span></button>{profileOpen&&<section className="profile-panel"><h3>Мой профиль</h3><label className="upload">Изменить аватар<input type="file" accept="image/png,image/jpeg,image/webp,image/gif" onChange={e=>uploadAvatar(e.target.files?.[0])}/></label><label>Имя<input value={me.displayName} onChange={e=>setMe({...me,displayName:e.target.value})} maxLength={40}/></label><label>О себе<textarea value={bio} onChange={e=>setBio(e.target.value)} maxLength={160}/></label><button onClick={saveProfile}>Сохранить</button><button className="link" onClick={logout}>Выйти</button></section>}<div className="section-title">СООБЩЕНИЯ</div><input className="search" placeholder="Найти по логину…" value={search} onChange={e=>setSearch(e.target.value)}/>{search&&results.map(u=><button className="person" key={u.id} onClick={()=>openChat(u)}><Avatar user={u}/><span><b>{u.displayName}</b><small>@{u.username}</small></span></button>)}{search&&results.length===0&&search.length>=2&&<p className="muted">Никого не найдено</p>}<div className="chat-list">{chats.map(c=><button className={active?.chatId===c.chatId?"chat active":"chat"} key={c.chatId} onClick={()=>selectChat(c)}><Avatar user={c.user}/><span><b>{c.user.displayName}</b><small>{c.lastMessage||"Начните общение"}</small></span></button>)}</div><footer>Личные сообщения</footer></aside><section className="conversation">{active?<><header className="conversation-head"><Avatar user={active.user}/><span><b>{active.user.displayName}</b><small>@{active.user.username}</small></span></header><div className="messages">{messages.map(m=><div key={m.id} className={m.senderId===me.id?"bubble mine":"bubble"}><p>{m.body}</p><time>{new Date(m.createdAt).toLocaleTimeString("ru-RU",{hour:"2-digit",minute:"2-digit"})}</time></div>)}</div><form className="composer" onSubmit={send}><input value={draft} onChange={e=>setDraft(e.target.value)} placeholder="Написать сообщение…" maxLength={4000}/><button disabled={!draft.trim()}>Отправить</button></form></>:<div className="empty"><div className="brand-mark">д</div><h2>Добро пожаловать в дтр</h2><p>Найди человека по логину, чтобы начать переписку.</p></div>}{error&&<button className="toast" onClick={()=>setError("")}>{error} ×</button>}</section></main>;
}
function Avatar({user}:{user:User}){return <span className="avatar">{user.avatarUrl?<img src={user.avatarUrl} alt=""/>:user.displayName.slice(0,1).toUpperCase()}</span>}
