package com.dominion.phoneflipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

private val bg = Color(0xFF0C0B14)
private val panel = Color(0xFF191726)
private val accent = Color(0xFF9A5CFF)
private val ink = Color(0xFFF7F3FF)
private val muted = Color(0xFFA6A1BA)
private val green = Color(0xFF70E5A0)

private data class Phone(val model: String, val market: Int)
private data class Lot(val id: Int, val model: String, val market: Int, val health: Int, val paid: Int, val repaired: Boolean = false)

private val phoneModels = listOf(
    Phone("VOLT V8", 27000), Phone("NOVA X", 44500), Phone("PULSE 12", 19800),
    Phone("PHANTOM PRO", 57800), Phone("TITAN MAX", 38900), Phone("ORBIT MINI", 15800),
    Phone("ASTRA Z", 24800), Phone("ZENITH 5", 33800)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("phone_flipper", MODE_PRIVATE)
        setContent {
            PhoneFlipper(prefs.getString("save", null)) { prefs.edit().putString("save", it).apply() }
        }
    }
}

@Composable
private fun PhoneFlipper(saved: String?, persist: (String) -> Unit) {
    var cash by remember { mutableIntStateOf(42000) }
    var level by remember { mutableIntStateOf(1) }
    var xp by remember { mutableIntStateOf(0) }
    var sales by remember { mutableIntStateOf(0) }
    var stock by remember { mutableStateOf(listOf<Lot>()) }
    var market by remember { mutableStateOf(newMarket()) }
    var tab by remember { mutableStateOf("Дом") }
    var selected by remember { mutableIntStateOf(-1) }
    var checked by remember { mutableStateOf(setOf<Int>()) }
    var note by remember { mutableStateOf("Найди выгодный лот и начни свой бизнес!") }
    var pixelTarget by remember { mutableIntStateOf(Random.nextInt(9)) }
    var pixelHits by remember { mutableIntStateOf(0) }
    var batteryTaps by remember { mutableIntStateOf(0) }
    var tradeLot by remember { mutableStateOf<MarketLot?>(null) }
    var tradeOffer by remember { mutableIntStateOf(0) }
    var tradeMessage by remember { mutableStateOf("") }

    fun save() {
        val root = JSONObject().put("cash", cash).put("level", level).put("xp", xp).put("sales", sales)
        val array = JSONArray()
        stock.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("model", p.model).put("market", p.market)
                .put("health", p.health).put("paid", p.paid).put("repaired", p.repaired))
        }
        persist(root.put("stock", array).toString())
    }
    fun gainXp(amount: Int) {
        xp += amount
        while (xp >= level * 100) {
            xp -= level * 100
            level++
            cash += 2500
            note = "Уровень $level! Бонус 2 500 ₽"
        }
    }

    LaunchedEffect(saved) {
        if (!saved.isNullOrBlank()) try {
            val root = JSONObject(saved)
            cash = root.optInt("cash", 42000)
            level = root.optInt("level", 1)
            xp = root.optInt("xp", 0)
            sales = root.optInt("sales", 0)
            val array = root.optJSONArray("stock") ?: JSONArray()
            stock = (0 until array.length()).mapNotNull { i ->
                val p = array.optJSONObject(i) ?: return@mapNotNull null
                Lot(p.optInt("id"), p.optString("model"), p.optInt("market"), p.optInt("health"),
                    p.optInt("paid"), p.optBoolean("repaired"))
            }
        } catch (_: Exception) { }
    }

    val current = stock.firstOrNull { it.id == selected } ?: stock.firstOrNull()
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = panel, onSurface = ink)) {
        Scaffold(containerColor = bg, bottomBar = {
            NavigationBar(containerColor = Color(0xFF12111D)) {
                listOf("Дом", "Рынок", "Диагностика", "Склад").forEach { label ->
                    NavigationBarItem(
                        selected = tab == label,
                        onClick = { tab = label; checked = emptySet() },
                        icon = { Text(when (label) { "Дом" -> "⌂"; "Рынок" -> "↗"; "Диагностика" -> "⌁"; else -> "▣" }, color = if (tab == label) accent else muted) },
                        label = { Text(label, fontSize = 10.sp) }
                    )
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxWidth().background(Color(0xFF211A38)).padding(16.dp)) {
                    Text("PHONE FLIPPER", color = ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Уровень $level   •   $cash ₽", color = Color(0xFFFFD66B), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { (xp / (level * 100f)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = accent)
                }
                when (tab) {
                    "Дом" -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { CardBox { Text("ТВОЙ МАГАЗИН", color = accent, fontWeight = FontWeight.Bold); Text(note, color = ink); Text("Продаж: $sales   •   Телефонов: ${stock.size}", color = muted) } }
                        item { CardBox { Text("Быстрый старт", color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold); Button(onClick = { tab = "Рынок" }, modifier = Modifier.fillMaxWidth()) { Text("Открыть рынок") } } }
                        item { CardBox { Text("Как играть", color = ink, fontWeight = FontWeight.Bold); Text("Покупай телефоны дешевле рыночной цены, проверяй 4 узла, ремонтируй и продавай с прибылью.", color = muted) } }
                    }
                    "Рынок" -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Text("Свежие объявления", color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        items(market) { lot ->
                            CardBox {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PhoneArt(lot.model.model, Modifier.width(58.dp).height(100.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(lot.model.model, color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text("Состояние: ${lot.health}%  •  Цена: ${lot.price} ₽", color = muted)
                                        Text("Рыночная цена: ${lot.model.market} ₽", color = green)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { tradeLot = lot; tradeOffer = (lot.price * 0.85).toInt(); tradeMessage = "Предложи цену продавцу." }) { Text("Торг") }
                                    Button(onClick = {
                                        if (cash < lot.price) note = "Не хватает денег."
                                        else {
                                            cash -= lot.price
                                            val bought = Lot(Random.nextInt(), lot.model.model, lot.model.market, lot.health, lot.price)
                                            stock = stock + bought
                                            selected = bought.id
                                            checked = emptySet()
                                            market = newMarket()
                                            note = "${bought.model} куплен. Проверь его!"
                                            save()
                                        }
                                    }, enabled = cash >= lot.price) { Text("Купить") }
                                }
                            }
                        }
                    }
                    "Диагностика" -> if (current == null) {
                        EmptyPanel("Склад пуст", "Купи первый телефон на рынке.") { tab = "Рынок" }
                    } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { CardBox { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PhoneArt(current.model, Modifier.width(72.dp).height(124.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(current.model, color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("Состояние ${current.health}%  •  Куплен за ${current.paid} ₽", color = muted)
                                Text(if (current.repaired) "После ремонта" else "Б/у • требует проверки", color = accent, fontSize = 12.sp)
                            }
                        } } }
                        item { CardBox {
                            Text("МИНИ-ИГРА • ПИКСЕЛИ", color = accent, fontWeight = FontWeight.Bold)
                            Text("Найди подсвеченный пиксель 3 раза, чтобы проверить экран.", color = muted)
                            Text("Попадания: $pixelHits / 3", color = ink, fontWeight = FontWeight.SemiBold)
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { (0..2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                (0..2).forEach { col -> val cell = row * 3 + col
                                    Button(onClick = { if (cell == pixelTarget) { pixelHits = (pixelHits + 1).coerceAtMost(3); if (pixelHits >= 3) { checked = checked + 0; note = "Тест экрана пройден!" }; pixelTarget = Random.nextInt(9) } else note = "Мимо! Найди подсвеченный пиксель." }, modifier = Modifier.weight(1f).height(42.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = if (cell == pixelTarget) accent else Color(0xFF302B43))) { Text(if (cell == pixelTarget) "◆" else "·", fontSize = 20.sp) }
                                }
                            } } }
                        } }
                        item { CardBox {
                            Text("МИНИ-ИГРА • БАТАРЕЯ", color = accent, fontWeight = FontWeight.Bold)
                            Text("Нажми импульс зарядки 5 раз.", color = muted)
                            Text("Импульсы: $batteryTaps / 5", color = ink, fontWeight = FontWeight.SemiBold)
                            Button(onClick = { batteryTaps++; if (batteryTaps >= 5) { checked = checked + 1; note = "Тест батареи пройден!" } }, enabled = batteryTaps < 5, modifier = Modifier.fillMaxWidth()) { Text(if (batteryTaps >= 5) "Батарея проверена ✓" else "⚡ Импульс зарядки") }
                        } }
                        items(listOf("Экран", "Батарея", "Камеры", "Звук").withIndex().toList()) { indexed ->
                            CardBox(Modifier.clickable { if (indexed.index >= 2) checked = checked + indexed.index else note = "Пройди мини-игру для проверки ${indexed.value.lowercase()}!" }) {
                                Text("${if (indexed.index in checked) "✓" else "○"}  ${indexed.value}", color = if (indexed.index in checked) green else ink, fontWeight = FontWeight.Bold)
                                Text(if (indexed.index in checked) "Узел проверен" else "Нажми для проверки", color = muted, fontSize = 12.sp)
                            }
                        }
                        item {
                            CardBox {
                                Text("Проверено: ${checked.size}/4", color = ink)
                                Button(onClick = {
                                    if (checked.size < 4) note = "Проверь все четыре узла."
                                    else if (current.repaired) note = "Уже отремонтирован."
                                    else if (cash < 4000) note = "Для ремонта нужно 4 000 ₽."
                                    else {
                                        cash -= 4000
                                        stock = stock.map { if (it.id == current.id) it.copy(health = (it.health + 17).coerceAtMost(100), repaired = true) else it }
                                        gainXp(30)
                                        note = "Ремонт выполнен. Рыночная стоимость выросла!"
                                        save()
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Ремонт • 4 000 ₽") }
                                OutlinedButton(onClick = {
                                    if (checked.size < 4) note = "Сначала проверь все узлы."
                                    else {
                                        val sale = current.market * current.health / 100 + if (current.repaired) 6500 else 0
                                        cash += sale
                                        sales++
                                        stock = stock.filterNot { it.id == current.id }
                                        selected = -1
                                        checked = emptySet()
                                        gainXp(45)
                                        note = "Продано за $sale ₽. Прибыль: ${sale - current.paid} ₽"
                                        save()
                                        tab = "Склад"
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Продать • ${current.market * current.health / 100} ₽") }
                            }
                        }
                    }
                    else -> if (stock.isEmpty()) {
                        EmptyPanel("Склад пуст", "Начни с выгодной покупки.") { tab = "Рынок" }
                    } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Text("Твой склад • ${stock.size}", color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        items(stock) { p ->
                            CardBox(Modifier.clickable { selected = p.id; checked = emptySet(); tab = "Диагностика" }) {
                                Text(p.model, color = ink, fontWeight = FontWeight.Bold)
                                Text("Состояние ${p.health}%  •  Куплен за ${p.paid} ₽", color = muted)
                                Text("Оценка ${p.market} ₽", color = green)
                                Text(if (p.repaired) "Отремонтирован" else "Нажми для диагностики", color = accent, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        tradeLot?.let { lot ->
            AlertDialog(
                onDismissRequest = { tradeLot = null },
                containerColor = panel,
                title = { Text("Торг • ${lot.model.model}", color = ink, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Цена продавца: ${lot.price} ₽", color = muted)
                        Text("Твоё предложение: ${tradeOffer} ₽", color = ink, fontWeight = FontWeight.Bold)
                        Slider(value = tradeOffer.toFloat(), onValueChange = { tradeOffer = it.toInt() }, valueRange = (lot.price * 0.45f)..lot.price.toFloat())
                        Text(tradeMessage, color = accent)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val minimum = (lot.model.market * 0.68).toInt()
                        if (tradeOffer >= lot.price) {
                            market = market.map { if (it == lot) it.copy(price = tradeOffer) else it }
                            tradeLot = null
                            note = "Торг завершён: ${tradeOffer} ₽"
                        } else if (tradeOffer >= minimum) {
                            val counter = ((tradeOffer + lot.price) / 2).coerceAtMost(lot.price)
                            market = market.map { if (it == lot) it.copy(price = counter) else it }
                            tradeLot = null
                            note = "Продавец уступил: ${counter} ₽"
                        } else {
                            tradeMessage = "Слишком низко. Продавец просит около ${minimum} ₽."
                        }
                    }) { Text("Предложить") }
                },
                dismissButton = { TextButton(onClick = { tradeLot = null }) { Text("Отмена") } }
            )
        }
    }
}

@Composable
private fun PhoneArt(model: String, modifier: Modifier = Modifier) {
    val palette = when (model) {
        "VOLT V8" -> listOf(Color(0xFF5F8CFF), Color(0xFF151D40)); "NOVA X" -> listOf(Color(0xFFFF8DCE), Color(0xFF43204A))
        "PULSE 12" -> listOf(Color(0xFF70E5A0), Color(0xFF143E35)); "PHANTOM PRO" -> listOf(Color(0xFFFFB45E), Color(0xFF4B2B19))
        "TITAN MAX" -> listOf(Color(0xFFB6A5FF), Color(0xFF2C2354)); "ORBIT MINI" -> listOf(Color(0xFF63D9E8), Color(0xFF153D4B))
        "ASTRA Z" -> listOf(Color(0xFFFF6F7F), Color(0xFF4B1F36)); else -> listOf(Color(0xFFE6E1F5), Color(0xFF343344))
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRoundRect(Color(0xFF05050A), topLeft = Offset(w * .08f, h * .02f), size = Size(w * .84f, h * .96f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .16f, w * .16f))
        drawRoundRect(Brush.verticalGradient(palette), topLeft = Offset(w * .14f, h * .07f), size = Size(w * .72f, h * .86f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .13f, w * .13f))
        drawCircle(Color(0xFF080810), radius = w * .055f, center = Offset(w * .5f, h * .11f))
        drawCircle(palette[0].copy(alpha = .8f), radius = w * .22f, center = Offset(w * .5f, h * .55f))
        drawCircle(palette[1], radius = w * .16f, center = Offset(w * .5f, h * .55f))
        drawRoundRect(Color.White.copy(alpha = .18f), topLeft = Offset(w * .2f, h * .18f), size = Size(w * .06f, h * .45f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .03f, w * .03f))
        drawRoundRect(Color.White.copy(alpha = .5f), topLeft = Offset(w * .38f, h * .82f), size = Size(w * .24f, h * .018f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w, w))
    }
}

private fun newMarket(): List<MarketLot> = (0 until 5).map {
    val model = phoneModels.random()
    MarketLot(model, Random.nextInt(60, 98), (model.market * Random.nextDouble(0.55, 0.82)).toInt())
}
private data class MarketLot(val model: Phone, val health: Int, val price: Int)

@Composable
private fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(panel, RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun EmptyPanel(title: String, subtitle: String, action: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = muted)
        Button(onClick = action) { Text("Открыть рынок") }
    }
}
