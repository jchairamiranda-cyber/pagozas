package com.example.pagozas

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pagozas.db.Pago
import com.example.pagozas.service.ZasAutomatorService

class MainActivity : ComponentActivity() {

    private val vm: PagosViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F5F5)) {
                    PagoZasApp(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagoZasApp(vm: PagosViewModel) {
    val context = LocalContext.current
    val pagos by vm.pagos.collectAsState()
    var isRunning by remember { mutableStateOf(ZasAutomatorService.isRunning) }
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Borrar historial") },
            text = { Text("¿Eliminar todos los registros?") },
            confirmButton = {
                TextButton(onClick = { vm.clearAll(); showClearDialog = false }) {
                    Text("Borrar", color = Color(0xFFD32F2F))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancelar") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PagoZas", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFB71C1C),
                    titleContentColor = Color.White
                ),
                actions = {
                    if (pagos.isNotEmpty()) {
                        TextButton(onClick = { showClearDialog = true }) {
                            Text("Borrar", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            // ── Botón activar accesibilidad ──────────────────────────────────
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("1. Activar Servicio de Accesibilidad")
            }

            // ── Botón iniciar / detener ──────────────────────────────────────
            Button(
                onClick = {
                    isRunning = !isRunning
                    ZasAutomatorService.isRunning = isRunning
                    if (isRunning) {
                        ZasAutomatorService.getInstance()?.startAutomation()
                            ?: run {
                                val i = context.packageManager
                                    .getLaunchIntentForPackage("bec.vdb.direct")
                                if (i != null) context.startActivity(i)
                            }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFD32F2F) else Color(0xFF388E3C)
                )
            ) {
                Text(
                    text = if (isRunning) "⏹  DETENER" else "▶  INICIAR Automatización",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // ── Encabezado de la lista ───────────────────────────────────────
            if (pagos.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(Color(0xFFB71C1C), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("#",         color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                    Text("Referencia",color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("Monto",     color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(90.dp))
                }
            }

            // ── Lista de pagos o pantalla vacía ──────────────────────────────
            if (pagos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sin cobros registrados aún", color = Color.Gray, fontSize = 17.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Activa el servicio y presiona INICIAR",
                            color = Color.LightGray, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(pagos) { index, pago ->
                        PagoItem(numero = index + 1, pago = pago)
                    }
                }
            }
        }
    }
}

@Composable
fun PagoItem(numero: Int, pago: Pago) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Número
            Text(
                text = "$numero",
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.width(28.dp)
            )
            // Referencia + fecha
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pago.codigo,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF1A1A1A)
                )
                Text(
                    text = pago.fecha,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            // Monto
            Text(
                text = pago.monto,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color(0xFF388E3C),
                textAlign = TextAlign.End,
                modifier = Modifier.width(90.dp)
            )
        }
    }
}
