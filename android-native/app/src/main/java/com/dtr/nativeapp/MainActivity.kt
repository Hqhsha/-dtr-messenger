package com.dominion.phoneflipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

private val night = Color(0xFF0C0B14)
private val card = Color(0xFF191726)
private val purple = Color(0xFF9A5CFF)
private val white = Color(0xFFF7F3FF)
private val gray = Color(0xFFA6A1BA)
private val lime = Color(0xFF70E5A0)

private data class Model(val title:String,val tint:Long,val base:Int)
private data class Lot(val id:Int,val model:String,val tint:Long,val health:Int,val cost:Int,val value:Int,val inspected:Boolean=false,val repaired:Boolean=false)
private val models = listOf(
 Model("VOLT V8",0xFF8055E8,27000), Model("NOVA X",0xFF2E9ED4,44500),
 Model("PULSE 12",0xFF25A78C,19800), Model("PHANTOM PRO",0xFFCA5AA2,57800),
 Model("TITAN MAX",0xFFD28A45,38900), Model("ORBIT MINI",0xFF7589E8,15800),
 Model("ASTRA Z",0xFF40A8B9,24800), Model("ZENITH 5",0xFFB5A05B,33800)
)
private data class MarketLot(val model:Model,val health:Int,val price:Int)

class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val prefs=getSharedPreferences("phone_flipper",MODE_PRIVATE)
  setContent { FlipperGame(prefs.getString("save",null)){prefs.edit().putString("save",it).apply()} }
 }
}

@Composable private fun FlipperGame(saved:String?,save:(String)->Unit){
 var cash by remember { mutableIntStateOf(42000) }
 var level by remember { mutableIntStateOf(1) }
 var xp by remember { mutableIntStateOf(0) }
 var totalSales by remember { mutableIntStateOf(0) }
 var stock by remember { mutableStateOf(listOf<Lot>()) }
 var market by remember { mutableStateOf(makeMarket()) }
 var page by remember { mutableStateOf("Дом") }
 var activeId by remember { mutableIntStateOf(-1) }
 var checked by remember { mutableStateOf(setOf<Int>()) }
 var message by remember { mutableStateOf("Добро пожаловать! Найди выгодный лот и начни свой бизнес.") }

 fun saveGame(){
  val root=JSONObject().put("cash",cash).put("level",level).put("xp",xp).put("sales",totalSales)
  val arr=JSONArray()
  stock.forEach { p->arr.put(JSONObject().put("id",p.id).put("model",p.model).put("tint",p.tint).put("health",p.health).put("cost",p.cost).put("value",p.value).put("inspected",p.inspected).put("repaired",p.repaired)) }
  root.put("stock",arr);save(root.toString())
 }
 fun addXp(amount:Int){xp+=amount;while(xp>=level*100){xp-=level*100;level++;cash+=2500;message="Новый уровень $level! Бонус +2 500 ₽"}}
 LaunchedEffect(saved){if(!saved.isNullOrBlank())try{
  val j=JSONObject(saved);cash=j.optInt("cash",42000);level=j.optInt("level",1);xp=j.optInt("xp",0);totalSales=j.optInt("sales",0)
  val a=j.optJSONArray("stock")?:JSONArray()
  stock=(0 until a.length()).mapNotNull{val p=a.optJSONObject(it)?:return@mapNotNull null;Lot(p.optInt("id"),p.optString("model"),p.optLong("tint"),p.optInt("health"),p.optInt("cost"),p.optInt("value"),p.optBoolean("inspected"),p.optBoolean("repaired"))}
 }catch(_:Exception){}}
 val active=stock.firstOrNull{it.id==activeId}?:stock.firstOrNull()
 MaterialTheme(colorScheme=darkColorScheme(primary=purple,background=night,surface=card,onSurface=white)){
  Scaffold(containerColor=night,bottomBar={NavigationBar(containerColor=Color(0xFF12111D)){listOf("Дом","Рынок","Диагностика","Склад").forEach{item->NavigationBarItem(selected=page==item,onClick={page=item;checked=setOf()},icon={Text(when(item){"Дом"->"⌂";"Рынок"->"↗";"Диагностика"->"⌁";else->"▣"},fontSize=21.sp,color=if(page==item)purple else gray)},label={Text(item,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(indicatorColor=Color(0xFF2B2042)))}})}){
  padding->Column(Modifier.fillMaxSize().padding(padding)){
   TopPanel(cash,level,xp)
   when(page){
    "Дом"->LazyColumn(contentPadding=PaddingValues(bottom=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
     item{GameCard(Modifier.padding(horizontal=16.dp)){Text("ТВОЙ МАГАЗИН",color=purple,fontSize=11.sp,fontWeight=FontWeight.Bold);Text("Покупай умнее. Продавай дороже.",color=white,fontSize=19.sp,fontWeight=FontWeight.Bold);Text(message,color=gray,fontSize=13.sp);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){MiniStat(stock.size.toString(),"На складе");MiniStat(totalSales.toString(),"Продано");MiniStat(level.toString(),"Уровень")}}}
     item{SectionTitle("Быстрый старт")}
     item{Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){ActionTile("↗","Рынок","Новые объявления",Modifier.weight(1f)){page="Рынок"};ActionTile("⌁","Проверка","Диагностика",Modifier.weight(1f)){page="Диагностика"}}}
     item{SectionTitle("Коллекция моделей")}
     item{Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){models.take(2).forEach{m->GameCard(Modifier.weight(1f)){PhoneArt(m.title,m.tint,Modifier.fillMaxWidth().height(125.dp));Text(m.title,color=white,fontWeight=FontWeight.Bold);Text("До ${m.base} ₽",color=gray,fontSize=12.sp)}}}}
     item{GameCard(Modifier.padding(horizontal=16.dp)){Text("СОХРАНЕНИЕ",color=purple,fontWeight=FontWeight.Bold,fontSize=11.sp);Text("Прогресс автоматически хранится на этом устройстве.",color=gray,fontSize=13.sp)}}
    }
    "Рынок"->LazyColumn(contentPadding=PaddingValues(bottom=16.dp)){
     item{SectionTitle("Свежие лоты")}
     item{Text("Смотри состояние, торгуйся и выбирай с умом.",color=gray,fontSize=13.sp,modifier=Modifier.padding(horizontal=16.dp))}
     items(market){lot->MarketCard(lot,cash,{newPrice->
       market=market.map{if(it.model.title==lot.model.title)it.copy(price=newPrice)else it};message="Продавец согласился на цену $newPrice ₽"
      },{if(cash>=lot.price){cash-=lot.price;val p=Lot(Random.nextInt(),lot.model.title,lot.model.tint,lot.health,lot.price,lot.model.base);stock=stock+p;activeId=p.id;checked=setOf();market=makeMarket();message="${p.model} куплен! Проверь его перед продажей.";saveGame()}else message="Не хватает денег для покупки."})}
    }
    "Диагностика"->{
     if(active==null)EmptyState("Пока нет телефона","Купи первый лот на рынке.",{page="Рынок"})
     else LazyColumn(contentPadding=PaddingValues(bottom=16.dp)){
      item{SectionTitle("Техническая проверка")}
      item{GameCard(Modifier.padding(horizontal=16.dp)){PhoneArt(active.model,active.tint,Modifier.fillMaxWidth().height(180.dp));Text(active.model,color=white,fontSize=20.sp,fontWeight=FontWeight.Bold);Text("Состояние: ${active.health}%  •  Закупка: ${active.cost} ₽",color=gray,fontSize=13.sp);Text("Найди неисправности. Проверка всех 4 узлов откроет ремонт и продажу.",color=gray,fontSize=12.sp)}}
      items(listOf("Экран","Батарея","Камеры","Звук").withIndex().toList()){indexed->val i=indexed.index;val name=indexed.value
       GameCard(Modifier.padding(horizontal=16.dp).clickable{checked=checked+i;message="Диагностика: $name"}){
        Row(verticalAlignment=Alignment.CenterVertically){Text(if(i in checked)"✓" else "○",color=if(i in checked)lime else purple,fontSize=22.sp);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(name,color=white,fontWeight=FontWeight.Bold);Text(if(i in checked)"Узел проверен" else "Нажми, чтобы проверить",color=gray,fontSize=12.sp)};Text(if(i in checked)"OK" else "SCAN",color=purple,fontSize=11.sp,fontWeight=FontWeight.Bold)}
       }
      }
      item{GameCard(Modifier.padding(horizontal=16.dp)){Text("Прогресс: ${checked.size}/4",color=white);Button(onClick={
       if(checked.size<4)message="Сначала проверь все четыре узла."
       else if(active.repaired)message="Этот телефон уже отремонтирован."
       else if(cash<4000)message="Для ремонта нужно 4 000 ₽."
       else{cash-=4000;val fixed=active.copy(health=(active.health+17).coerceAtMost(100),value=active.value+6500,repaired=true,inspected=true);stock=stock.map{if(it.id==active.id)fixed else it};activeId=fixed.id;addXp(30);message="Ремонт готов! Рыночная цена выросла.";saveGame()}
      },modifier=Modifier.fillMaxWidth()){Text("Ремонт • 4 000 ₽")}
      OutlinedButton(onClick={
       if(checked.size<4)message="Сначала заверши диагностику."
       else{val sale=active.value*active.health/100;cash+=sale;totalSales++;stock=stock.filterNot{it.id==active.id};activeId=-1;addXp(45);message="Продано за $sale ₽. Результат сделки: ${sale-active.cost} ₽";checked=setOf();saveGame();page="Склад"}
      },modifier=Modifier.fillMaxWidth()){Text("Продать • ${active.value*active.health/100} ₽")}
      }}
     }
    }
    else->{
     if(stock.isEmpty())EmptyState("Склад пуст","Начни с выгодной покупки.",{page="Рынок"})
     else LazyColumn(contentPadding=PaddingValues(bottom=16.dp)){item{SectionTitle("Твой склад • ${stock.size}")};items(stock){p->GameCard(Modifier.padding(horizontal=16.dp).clickable{activeId=p.id;checked=setOf();page="Диагностика"}){Row(verticalAlignment=Alignment.CenterVertically){PhoneArt(p.model,p.tint,Modifier.width(90.dp).height(115.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(p.model,color=white,fontWeight=FontWeight.Bold);Text("Состояние ${p.health}%",color=gray);Text("Покупка ${p.cost} ₽",color=gray,fontSize=12.sp);Text("Оценка ${p.value} ₽",color=lime,fontWeight=FontWeight.Bold);Text(if(p.repaired)"Отремонтирован" else "Нужна диагностика",color=purple,fontSize=11.sp)}}}}}
    }
   }
  }
 }
 }
}
private fun makeMarket():List<MarketLot> = (0 until 5).map{val m=models.random();val health=Random.nextInt(62,98);MarketLot(m,health,(m.base*Random.nextDouble(.55,.82)).toInt())}
@Composable private fun TopPanel(cash:Int,level:Int,xp:Int){Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF211A38),night))).padding(horizontal=18.dp,vertical=16.dp)){Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(purple),contentAlignment=Alignment.Center){Text("PF",color=white,fontSize=18.sp,fontWeight=FontWeight.Black)};Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text("PHONE FLIPPER",color=white,fontSize=20.sp,fontWeight=FontWeight.ExtraBold);Text("2D PHONE DEALER SIM",color=gray,fontSize=10.sp)};Column(horizontalAlignment=Alignment.End){Text("УРОВЕНЬ $level",color=purple,fontSize=11.sp,fontWeight=FontWeight.Bold);Text("$cash ₽",color=Color(0xFFFFD66B),fontSize=18.sp,fontWeight=FontWeight.Bold)}};Spacer(Modifier.height(10.dp));LinearProgressIndicator(progress={(xp/(level*100f)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),color=purple,trackColor=Color(0xFF302B43)}}
@Composable private fun SectionTitle(title:String){Text(title,color=white,fontSize=19.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(start=18.dp,end=18.dp,top=16.dp,bottom=2.dp))}
@Composable private fun GameCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(card).border(1.dp,Color(0xFF302B45),RoundedCornerShape(20.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp),content=content)}
@Composable private fun MiniStat(value:String,label:String){Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF252238)).padding(10.dp),horizontalAlignment=Alignment.Center){Text(value,color=white,fontSize=19.sp,fontWeight=FontWeight.Bold);Text(label,color=gray,fontSize=10.sp)}}
@Composable private fun ActionTile(icon:String,title:String,sub:String,modifier:Modifier=Modifier,onClick:()->Unit){Column(modifier.clip(RoundedCornerShape(17.dp)).background(card).border(1.dp,Color(0xFF302B45),RoundedCornerShape(17.dp)).clickable(onClick=onClick).padding(14.dp)){Text(icon,color=purple,fontSize=28.sp);Text(title,color=white,fontWeight=FontWeight.Bold);Text(sub,color=gray,fontSize=12.sp)}}
@Composable private fun MarketCard(lot:MarketLot,cash:Int,onBargain:(Int)->Unit,onBuy:()->Unit){GameCard(Modifier.padding(horizontal=16.dp,vertical=6.dp)){Row(verticalAlignment=Alignment.CenterVertically){PhoneArt(lot.model.title,lot.model.tint,Modifier.width(84.dp).height(128.dp));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(lot.model.title,color=white,fontWeight=FontWeight.Bold,fontSize=16.sp);Text("Состояние ${lot.health}%",color=gray,fontSize=12.sp);Text("Продавец: ${lot.price} ₽",color=white);Text("Рыночная: ${lot.model.base} ₽",color=lime,fontSize=12.sp);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(onClick={onBargain((lot.price- Random.nextInt(700,2200)).coerceAtLeast(1000))},contentPadding=PaddingValues(horizontal=10.dp)){Text("Торг",fontSize=12.sp)};Button(onClick=onBuy,contentPadding=PaddingValues(horizontal=10.dp),enabled=cash>=lot.price){Text("Купить",fontSize=12.sp)}}}}}
@Composable private fun EmptyState(title:String,sub:String,action:()->Unit){Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,color=white,fontSize=22.sp,fontWeight=FontWeight.Bold);Text(sub,color=gray);Button(onClick=action){Text("Открыть рынок")}}}
@Composable private fun PhoneArt(name:String,tint:Long,modifier:Modifier=Modifier){Canvas(modifier){val w=size.width;val h=size.height;val phoneW=w.coerceAtMost(h*.48f);val phoneH=h*.92f;val left=(w-phoneW)/2;val top=(h-phoneH)/2;drawRoundRect(Color(0xFF05050A),Offset(left,top),Size(phoneW,phoneH),androidx.compose.ui.geometry.CornerRadius(phoneW*.13f));drawRoundRect(Color(0xFF55536A),Offset(left+phoneW*.025f,top+phoneH*.012f),Size(phoneW*.95f,phoneH*.976f),androidx.compose.ui.geometry.CornerRadius(phoneW*.12f));drawRoundRect(Color(0xFF10101A),Offset(left+phoneW*.06f,top+phoneH*.035f),Size(phoneW*.88f,phoneH*.93f),androidx.compose.ui.geometry.CornerRadius(phoneW*.1f));val c=Color(tint);drawRoundRect(Brush.linearGradient(listOf(c,c.copy(alpha=.55f),Color(0xFF181725))),Offset(left+phoneW*.09f,top+phoneH*.06f),Size(phoneW*.82f,phoneH*.88f),androidx.compose.ui.geometry.CornerRadius(phoneW*.09f));repeat(3){i->drawCircle(Color(0xFF080811),phoneW*.065f,Offset(left+phoneW*.19f+(i%2)*phoneW*.14f,top+phoneH*.13f+(i/2)*phoneW*.14f))};drawRoundRect(Color.White.copy(alpha=.25f),Offset(left+phoneW*.42f,top+phoneH*.08f),Size(phoneW*.16f,phoneH*.012f),androidx.compose.ui.geometry.CornerRadius(20f));drawCircle(Color.White.copy(alpha=.55f),phoneW*.08f,Offset(w/2,h*.52f));drawCircle(c.copy(alpha=.8f),phoneW*.16f,Offset(w/2,h*.52f),style=androidx.compose.ui.graphics.drawscope.Stroke(phoneW*.025f))}}
