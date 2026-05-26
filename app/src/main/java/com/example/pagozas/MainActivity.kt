package com.example.pagozas

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pagozas.db.Pago
import com.example.pagozas.db.PagoZasDatabase
import com.example.pagozas.service.ZasAutomatorService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PagoZasApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagoZasApp() {
    val context = LocalContext.current
    val db = PagoZasDatabase.getDatabase(context)
    val pagosList by db.pagoDao().getAllPagos().collectAsState(initial = emptyList())
    var isRunning by remember { mutableStateOf(ZasAutomatorService.isRunning) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PagoZas - Extractor ZA\$", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFB71C1C),
                    titleContentColor = Color.White,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            // ─── Botón 1: Accesibilidad ───────────────────────────────────────
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("1. Activar Servicio de Accesibilidad")
            }

            // ─── Botón 2: Iniciar / Detener automatización ────────────────────
            Button(
                onClick = {
                    isRunning = !isRunning
                    ZasAutomatorService.isRunning = isRunning
                    if (isRunning) {
                        // Llamar startAutomation() en el servicio si ya está conectado
                        ZasAutomatorService.getInstance()?.startAutomation()
                            ?: run {
                                // Si el servicio aún no está conectado, abrir la app destino directamente
                                val launchIntent = context.packageManager.getLaunchIntentForPackage("bec.vdb.direct")
                                if (launchIntent != null) {
                                    context.startActivity(launchIntent)
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        "⚠️ App ZA\$ no encontrada. Instálala primero.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFD32F2F) else Color(0xFF388E3C)
                )
            ) {
                Text(
                    text = if (isRunning) "⏹ DETENER Automatización" else "▶ INICIAR Automatización",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // ─── Contador de pagos extraídos ──────────────────────────────────
            if (pagosList.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(Color(0xFFF5F5F5), shape = MaterialTheme.shapes.small)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Pagos registrados:", fontWeight = FontWeight.Medium)
                    Text(
                        "${pagosList.size}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF388E3C)
                    )
                }
            }

            // ─── Lista de pagos ───────────────────────────────────────────────
            if (pagosList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sin pagos aún", color = Color.Gray, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Activa el servicio y presiona INICIAR",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(pagosList) { pago ->
                        PagoItem(pago = pago)
                    }
                }
            }
        }
    }
}

@Composable
fun PagoItem(pago: Pago) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pago.codigo,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF1A1A1A)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pago.fecha,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Text(
                text = pago.monto,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF388E3C)
            )
        }
    }
}
