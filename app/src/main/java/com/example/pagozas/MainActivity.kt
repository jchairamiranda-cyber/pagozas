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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    var showClearDialog  by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }

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

    if (showConfigDialog) {
        ConfigDialog(onDismiss = { showConfigDialog = false })
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
                    TextButton(onClick = { showConfigDialog = true }) {
                        Text("Config", color = Color.White, fontSize = 13.sp)
                    }
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
            Text(
                text = "$numero",
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.width(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pago.codigo,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF1A1A1A)
                    )
                    if (pago.enviado) {
                        Spacer(Modifier.width(6.dp))
                        Text("✓", fontSize = 12.sp, color = Color(0xFF388E3C))
                    }
                }
                Text(text = pago.fecha, fontSize = 11.sp, color = Color.Gray)
            }
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

@Composable
fun ConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var backendUrl by remember { mutableStateOf(PagozasConfig.backendUrl(context)) }
    var token      by remember { mutableStateOf(PagozasConfig.token(context)) }
    var workerId   by remember { mutableStateOf(PagozasConfig.workerId(context)) }
    var provider   by remember { mutableStateOf(PagozasConfig.provider(context)) }
    var pin        by remember { mutableStateOf(PagozasConfig.pin(context)) }
    var showToken  by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configuración", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    label = { Text("Backend URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showToken = !showToken }) {
                            Text(if (showToken) "Ocultar" else "Ver", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = workerId,
                    onValueChange = { workerId = it },
                    label = { Text("Worker ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = provider,
                    onValueChange = { provider = it },
                    label = { Text("Provider") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                PagozasConfig.save(context, backendUrl, token, workerId, provider, pin)
                onDismiss()
            }) {
                Text("Guardar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
