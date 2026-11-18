package com.avcan.fiyatetiket

import android.os.Bundle
import android.content.Context
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF12304A)
private val Blue = Color(0xFF1769AA)
private val Pale = Color(0xFFF3F6F9)

@Suppress("DEPRECATION")
private fun appVersionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Bilinmiyor"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AuthGate() } }
        UpdateManager.checkForUpdate(this)
    }
}

data class AppUser(
    val uid: String,
    val email: String,
    val fullName: String,
    val role: String,
    val branchCode: String,
    val branchName: String = ""
)

data class PriceChange(
    val id: String,
    val barcode: String,
    val name: String,
    val oldPrice: String,
    val newPrice: String,
    val branchCode: String,
    val branch: String,
    val batchDate: String,
    val read: Boolean = false,
    val delivered: Boolean = false,
    val changed: Boolean = false
) {
    val status: String
        get() = when {
            changed -> "Tamamlandı"
            delivered -> "Teslim alındı"
            read -> "Okundu"
            else -> "Etiket bekliyor"
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthGate() {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val context = LocalContext.current
    val versionName = remember { appVersionName(context) }
    val prefs = remember {
        context.getSharedPreferences("avcan_login_preferences", Context.MODE_PRIVATE)
    }

    // Yalnızca kullanıcı adı hatırlanır; şifre cihazda saklanmaz.
    var username by remember { mutableStateOf(prefs.getString("saved_username", prefs.getString("saved_email", "")) ?: "") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(prefs.getBoolean("remember_email", true)) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var appUser by remember { mutableStateOf<AppUser?>(null) }

    fun finishLogin(uid: String, userEmail: String, document: com.google.firebase.firestore.DocumentSnapshot) {
        val role = document.getString("role").orEmpty()
        val branchCode = document.getString("branchCode").orEmpty()
        val fullName = document.getString("fullName").orEmpty()

        if (!document.exists() || document.getString("approvalStatus") != "approved" ||
            role !in listOf("admin", "owner_admin", "branch_admin", "staff")
        ) {
            auth.signOut()
            loading = false
            message = "Hesabınız onaylı değil veya yetkiniz bulunmuyor."
            return
        }

        if (role == "staff" || role == "branch_admin") {
            if (branchCode.isBlank()) {
                auth.signOut()
                loading = false
                message = "Hesabınıza henüz şube atanmamış. Yöneticinizle görüşün."
                return
            }
            db.collection("branches").document(branchCode).get()
                .addOnSuccessListener { branchDocument ->
                    val active = branchDocument.getBoolean("active") ?: false
                    val branchName = branchDocument.getString("name").orEmpty()
                    if (branchDocument.exists() && active && branchName.isNotBlank()) {
                        appUser = AppUser(uid, userEmail, fullName, role, branchCode, branchName)
                        loading = false
                    } else {
                        auth.signOut()
                        loading = false
                        message = "Atandığınız şube bulunamadı veya pasif. Yöneticiyle görüşün."
                    }
                }
                .addOnFailureListener {
                    auth.signOut()
                    loading = false
                    message = "Şube bilgisi alınamadı. Firestore erişim kurallarını kontrol edin."
                }
        } else {
            appUser = AppUser(uid, userEmail, fullName, role, branchCode, "")
            loading = false
        }
    }

    // Firebase oturumu hâlâ açıksa tekrar giriş isteme; kullanıcı ve şubesini doğrula.
    LaunchedEffect(Unit) {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            loading = true
            db.collection("users").document(currentUser.uid).get()
                .addOnSuccessListener { document ->
                    finishLogin(currentUser.uid, currentUser.email.orEmpty(), document)
                }
                .addOnFailureListener {
                    auth.signOut()
                    loading = false
                    message = "Kullanıcı bilgileri alınamadı. İnternet ve Firestore kurallarını kontrol edin."
                }
        }
    }

    val signedInUser = appUser
    if (signedInUser != null) {
        AvcanApp(
            user = signedInUser,
            onSignOut = {
                auth.signOut()
                appUser = null
                password = ""
            }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Pale).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("AVCAN GIDA", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Navy)
        Text("Fiyat ve Etiket Takip Sistemi")
        Text("Yapımcı: İbrahim Kayan", fontSize = 12.sp, color = Color.Gray)
        Text("Sürüm $versionName", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Kullanıcı adı") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Şifre") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = rememberMe,
                onCheckedChange = { checked ->
                    rememberMe = checked
                    prefs.edit().putBoolean("remember_email", checked).apply()
                    if (!checked) prefs.edit().remove("saved_username").remove("saved_email").apply()
                }
            )
            Text(
                "Beni hatırla",
                modifier = Modifier.clickable {
                    rememberMe = !rememberMe
                    prefs.edit().putBoolean("remember_email", rememberMe).apply()
                    if (!rememberMe) prefs.edit().remove("saved_username").remove("saved_email").apply()
                }
            )
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                if (username.isBlank() || password.isBlank()) {
                    message = "Kullanıcı adı ve şifre giriniz."
                    return@Button
                }
                val loginName = username.trim()
                // Personel kullanıcı adlarının sonunda şube kodu bulunur: ahmet10 -> 10.ahmet10@users.avcan.local
                val loginEmail = if (loginName.contains("@")) {
                    loginName
                } else {
                    val match = Regex("^(.+?)(10|20|40|41|95|96)$").matchEntire(loginName)
                    if (match != null) "${match.groupValues[2]}.$loginName@users.avcan.local" else ""
                }
                if (loginEmail.isBlank()) {
                    message = "Kullanıcı adı tanınmadı. Şube koduyla biten kullanıcı adınızı giriniz."
                    return@Button
                }
                loading = true
                message = ""
                auth.signInWithEmailAndPassword(loginEmail, password)
                    .addOnSuccessListener { result ->
                        val uid = result.user?.uid.orEmpty()
                        db.collection("users").document(uid).get()
                            .addOnSuccessListener { document ->
                                if (document.exists() &&
                                    document.getString("approvalStatus") == "approved" &&
                                    document.getString("role") in listOf("admin", "owner_admin", "branch_admin", "staff")
                                ) {
                                    if (rememberMe) {
                                        prefs.edit()
                                            .putBoolean("remember_email", true)
                                            .putString("saved_username", loginName)
                                            .apply()
                                    } else {
                                        prefs.edit().remove("saved_username").remove("saved_email").apply()
                                    }
                                }
                                finishLogin(uid, result.user?.email ?: loginEmail, document)
                            }
                            .addOnFailureListener {
                                auth.signOut()
                                loading = false
                                message = "Kullanıcı bilgileri alınamadı. Firestore kurallarını kontrol edin."
                            }
                    }
                    .addOnFailureListener {
                        loading = false
                        message = "Giriş başarısız. Kullanıcı adı ve şifrenizi kontrol edin."
                    }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading
        ) {
            Text(if (loading) "Kontrol ediliyor..." else "Giriş Yap")
        }

        if (loading) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator()
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = Color.Red)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvcanApp(user: AppUser, onSignOut: () -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    val products = remember { mutableStateListOf<PriceChange>() }
    var page by remember { mutableStateOf("Özet") }
    var selected by remember { mutableStateOf<PriceChange?>(null) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var loadMessage by remember { mutableStateOf("Firebase kayıtları yükleniyor...") }
    var actionMessage by remember { mutableStateOf("") }
    var showExitDialog by remember { mutableStateOf(false) }
    var lastHomeBackAt by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val versionName = remember { appVersionName(context) }
    val canManage = user.role in listOf("admin", "owner_admin", "branch_admin")

    // Yönetim sayfası personel hesabında açılamaz.
    LaunchedEffect(canManage) {
        if (!canManage && page == "Yönetim") page = "Özet"
    }

    DisposableEffect(user.uid, user.role, user.branchCode) {
        var registration: ListenerRegistration? = null
        val baseQuery = db.collection("priceChanges")
            .whereEqualTo("published", true)
        val query = if (user.role == "admin" || user.role == "owner_admin") {
            baseQuery
        } else {
            baseQuery.whereEqualTo("branchCode", user.branchCode)
        }
        registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                loadMessage = "Kayıtlar alınamadı: ${error.localizedMessage ?: "Firestore erişim hatası"}"
            } else if (snapshot != null) {
                products.clear()
                products.addAll(snapshot.documents.mapNotNull { doc ->
                    val barcode = doc.getString("barcode") ?: return@mapNotNull null
                    PriceChange(
                        id = doc.id,
                        barcode = barcode,
                        name = doc.getString("productName") ?: doc.getString("name") ?: "Ürün adı yok",
                        oldPrice = priceText(doc.get("oldPrice")),
                        newPrice = priceText(doc.get("newPrice")),
                        branchCode = doc.getString("branchCode") ?: "",
                        branch = doc.getString("branchName") ?: doc.getString("branch") ?: "Şube belirtilmemiş",
                        batchDate = dateText(doc.get("createdAt") ?: doc.get("batchDate")),
                        read = doc.getBoolean("read") ?: false,
                        delivered = doc.getBoolean("delivered") ?: false,
                        changed = doc.getBoolean("changed") ?: false
                    )
                })
                loadMessage = if (products.isEmpty()) "Henüz Firebase'e aktarılmış fiyat değişikliği yok." else ""
            }
        }
        onDispose { registration?.remove() }
    }

    fun updateStep(item: PriceChange, field: String, value: Boolean) {
        val allowed = when (field) {
            "delivered" -> !value || item.read
            "changed" -> !value || item.delivered
            else -> true
        }
        if (!allowed) {
            actionMessage = if (field == "delivered") "Önce fiyat değişikliğini okundu olarak işaretleyin." else "Önce görev teslim alındı adımını tamamlayın."
            return
        }
        val prefix = when (field) { "read" -> "read"; "delivered" -> "delivered"; else -> "changed" }
        val values = hashMapOf<String, Any?>(
            field to value,
            "${prefix}At" to if (value) FieldValue.serverTimestamp() else null,
            "${prefix}ByUid" to if (value) user.uid else null,
            "${prefix}ByEmail" to if (value) user.email else null,
            "updatedAt" to FieldValue.serverTimestamp(),
            "updatedByUid" to user.uid
        )
        db.collection("priceChanges").document(item.id).update(values)
            .addOnSuccessListener { actionMessage = "İşlem Firebase'e kaydedildi." }
            .addOnFailureListener { e -> actionMessage = "Kayıt yapılamadı: ${e.localizedMessage ?: "Firestore hatası"}" }
    }

    BackHandler {
        when {
            showExitDialog -> showExitDialog = false
            selected != null -> { selected = null; actionMessage = "" }
            selectedDate != null -> selectedDate = null
            page != "Özet" -> { page = "Özet"; selected = null; selectedDate = null }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastHomeBackAt <= 2000L) {
                    showExitDialog = true
                    lastHomeBackAt = 0L
                } else {
                    lastHomeBackAt = now
                }
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Uygulamadan çıkış") },
            text = { Text("Programdan çıkmak istediğinize emin misiniz?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    (context as? Activity)?.finish()
                }) { Text("Evet") }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Hayır") }
            }
        )
    }

    Scaffold(
        containerColor = Pale,
        topBar = {
            TopAppBar(
                title = { Column { Text("AVCAN GIDA", fontWeight = FontWeight.Bold); Text(
                    "Fiyat ve Etiket Takip · ${user.fullName.ifBlank { user.email }}${if (user.branchName.isNotBlank()) " · ${user.branchName}" else ""}",
                    fontSize = 11.sp,
                    color = Color.Gray
                ); Text("Yapımcı: İbrahim Kayan · Sürüm $versionName", fontSize = 10.sp, color = Color.Gray) } },
                actions = { IconButton(onClick = onSignOut) { Icon(Icons.Default.Logout, contentDescription = "Çıkış") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val navigationItems = if (canManage) {
                    listOf("Özet", "Ürünler", "İşlemler", "Yönetim")
                } else {
                    listOf("Özet", "Ürünler", "İşlemler")
                }
                navigationItems.forEach { item ->
                    NavigationBarItem(
                        selected = page == item,
                        onClick = { page = item; selected = null; selectedDate = null },
                        icon = { Icon(when (item) { "Özet" -> Icons.Default.Dashboard; "Ürünler" -> Icons.Default.Inventory; "İşlemler" -> Icons.Default.Checklist; else -> Icons.Default.AdminPanelSettings }, contentDescription = null) },
                        label = { Text(item) }
                    )
                }
            }
        }
    ) { padding ->
        when {
            selected != null -> ProductDetail(
                item = products.firstOrNull { it.id == selected!!.id } ?: selected!!,
                modifier = Modifier.padding(padding),
                message = actionMessage,
                onBack = { selected = null; actionMessage = "" },
                onUpdate = { item, field, value -> updateStep(item, field, value) }
            )
            page == "Özet" -> Dashboard(Modifier.padding(padding), products, loadMessage) { page = it }
            page == "Ürünler" -> ProductList(
                Modifier.padding(padding), products, loadMessage,
                selectedDate = selectedDate,
                onSelectDate = { selectedDate = it },
                onBackToDates = { selectedDate = null },
                onSelectProduct = { selected = it }
            )
            page == "İşlemler" -> TaskList(Modifier.padding(padding), products, loadMessage) { selected = it }
            page == "Yönetim" && canManage -> AdminPage(Modifier.padding(padding), user)
            else -> Dashboard(Modifier.padding(padding), products, loadMessage) { page = it }
        }
    }
}

@Composable
fun Dashboard(modifier: Modifier, products: List<PriceChange>, loadMessage: String, onOpen: (String) -> Unit) {
    val completed = products.count { it.changed }
    val waiting = products.count { !it.changed }
    val unread = products.count { !it.read }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Günlük durum", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Metric("Değişen ürün", products.size.toString(), Blue, Modifier.weight(1f)); Metric("Bekleyen etiket", waiting.toString(), Color(0xFFB7791F), Modifier.weight(1f)) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Metric("Tamamlanan", completed.toString(), Color(0xFF218653), Modifier.weight(1f)); Metric("Okunmayan", unread.toString(), Color(0xFFB83232), Modifier.weight(1f)) } }
        if (loadMessage.isNotBlank()) item { Text(loadMessage, color = Color.Gray, fontSize = 13.sp) }
        item { Button(onClick = { onOpen("Ürünler") }) { Text("Fiyat değişikliklerini görüntüle") } }
    }
}

@Composable
fun Metric(label: String, value: String, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp)) { Text(label, color = Color.Gray, fontSize = 12.sp); Text(value, color = color, fontSize = 27.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun ProductList(
    modifier: Modifier,
    products: List<PriceChange>,
    loadMessage: String,
    selectedDate: String?,
    onSelectDate: (String) -> Unit,
    onBackToDates: () -> Unit,
    onSelectProduct: (PriceChange) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = products.filter {
        it.name.contains(search, true) || it.barcode.contains(search)
    }
    val grouped = filtered.groupBy { it.batchDate.substringBefore(" ").ifBlank { "Tarih yok" } }
        .toSortedMap(compareByDescending { date ->
            try { SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR")).parse(date)?.time ?: 0L }
            catch (_: Exception) { 0L }
        })

    if (selectedDate == null) {
        LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Ürün grupları", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy) }
            item { OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Barkod veya ürün ara") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true) }
            if (loadMessage.isNotBlank()) item { Text(loadMessage, color = Color.Gray, fontSize = 13.sp) }
            if (grouped.isEmpty() && loadMessage.isBlank()) item { Text("Henüz yayımlanmış ürün kaydı bulunmuyor.", color = Color.Gray) }
            items(grouped.entries.toList(), key = { it.key }) { entry ->
                Card(
                    Modifier.fillMaxWidth().clickable { onSelectDate(entry.key) },
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Blue, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(entry.key, fontWeight = FontWeight.Bold, color = Navy)
                                Text("Bu tarihte gelen ürünler", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color(0xFFE4EFFB), shape = RoundedCornerShape(18.dp)) {
                                Text("${entry.value.size} ürün", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Navy, fontSize = 12.sp)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = "Ürünleri aç")
                        }
                    }
                }
            }
        }
    } else {
        val dateProducts = grouped[selectedDate].orEmpty()
        LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                TextButton(onClick = onBackToDates) {
                    Icon(Icons.Default.ArrowBack, null)
                    Text("Tarihlere dön")
                }
            }
            item { Text(selectedDate, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy) }
            item { Text("${dateProducts.size} ürün", color = Color.Gray, fontSize = 13.sp) }
            items(dateProducts, key = { it.id }) { item -> ProductCard(item) { onSelectProduct(item) } }
        }
    }
}

@Composable
fun ProductCard(item: PriceChange, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(item.name, fontWeight = FontWeight.Bold); StatusPill(item.status) }
            Text("Barkod: ${item.barcode}", color = Color.Gray, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) { Text(item.oldPrice, color = Color.Gray); Icon(Icons.Default.ArrowForward, null, Modifier.padding(horizontal = 8.dp).size(16.dp)); Text(item.newPrice, color = Color(0xFFB83232), fontWeight = FontWeight.Bold) }
            Text("${item.branch} · ${item.batchDate}", color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
fun StatusPill(status: String) {
    val bg = when (status) { "Tamamlandı" -> Color(0xFFE2F4E9); "Okundu", "Teslim alındı" -> Color(0xFFE4EFFB); else -> Color(0xFFFFF1D6) }
    Surface(color = bg, shape = RoundedCornerShape(20.dp)) { Text(status, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 11.sp, color = Navy) }
}

@Composable
fun TaskList(modifier: Modifier, products: List<PriceChange>, loadMessage: String, onSelect: (PriceChange) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Etiket işlemleri", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy) }
        if (loadMessage.isNotBlank()) item { Text(loadMessage, color = Color.Gray, fontSize = 13.sp) }
        items(products, key = { it.id }) { ProductCard(it) { onSelect(it) } }
    }
}

@Composable
fun ProductDetail(item: PriceChange, modifier: Modifier, message: String, onBack: () -> Unit, onUpdate: (PriceChange, String, Boolean) -> Unit) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null); Text("Geri") }
        Text(item.name, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Navy)
        Text("Barkod: ${item.barcode}")
        Text("${item.oldPrice}  →  ${item.newPrice}", color = Color(0xFFB83232), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Şube: ${item.branch}")
        Text("Yükleme: ${item.batchDate}")
        HorizontalDivider()
        Text("İşlem adımları", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = item.read, onCheckedChange = { if (!item.read) onUpdate(item, "read", true) }); Text("Fiyat değişikliği okundu") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = item.delivered, enabled = item.read, onCheckedChange = { if (!item.delivered) onUpdate(item, "delivered", true) }); Text("Etiket / görev teslim alındı") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = item.changed, enabled = item.delivered, onCheckedChange = { if (!item.changed) onUpdate(item, "changed", true) }); Text("Raf etiketi değiştirildi") }
        Text("Üçüncü adım tamamlandığında işlem tamamlanır. Fotoğraf yükleme gerekmiyor.", color = Color.Gray, fontSize = 12.sp)
        if (message.isNotBlank()) Text(message, color = if (message.startsWith("İşlem")) Color(0xFF218653) else Color.Red, fontSize = 12.sp)
    }
}

@Composable
fun AdminPage(modifier: Modifier, user: AppUser) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Yönetici paneli", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Icon(Icons.Default.UploadFile, null, tint = Blue, modifier = Modifier.size(34.dp))
                Text("Excel fiyat listesi yükle", fontWeight = FontWeight.Bold)
                Text("Excel yükleme bağlantısı henüz etkinleştirilmedi. Bu sürüm Firebase'deki fiyat değişikliklerini ve işlem durumlarını okur.", color = Color.Gray, fontSize = 12.sp)
            }
        }
        Text("Oturum: ${user.email}", color = Color.Gray, fontSize = 12.sp)
        Text(
            "Rol: ${user.role} · Şube: ${if (user.branchCode.isBlank()) "Tüm şubeler" else "${user.branchCode} · ${user.branchName}"}",
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}

private fun priceText(value: Any?): String = when (value) {
    is Number -> String.format(Locale("tr", "TR"), "%.2f ₺", value.toDouble())
    is String -> if (value.contains("₺")) value else "$value ₺"
    else -> "-"
}

private fun dateText(value: Any?): String = when (value) {
    is Timestamp -> SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR")).format(value.toDate())
    is Date -> SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR")).format(value)
    is String -> value
    else -> "Tarih yok"
}
