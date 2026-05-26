package com.example.pagozas.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.pagozas.db.Pago
import com.example.pagozas.db.PagoZasDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ZasAutomatorService : AccessibilityService() {

    companion object {
        var isRunning = false
    }

    private val TAG = "ZasAutomator"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastScrollTime = 0L
    private val SCROLL_DELAY_MS = 3000L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning) return
        
        val rootNode = rootInActiveWindow ?: return
        
        // Asumiendo que el paquete de la app es bec.vdb.direct o similar. Ajustar si es diferente.
        val packageName = event?.packageName?.toString() ?: ""
        // No filtramos por paquete para facilitar el debug, pero se puede hacer if(packageName == "bec.vdb.direct")

        // Paso 1: Pantalla de inicio (Buscar "Ingresar")
        // En tu imagen se ve "Ingresar ->]". Buscamos nodos que contengan "Ingresar"
        val btnIngresar = rootNode.findAccessibilityNodeInfosByText("Ingresar")
        if (btnIngresar.isNotEmpty()) {
            val node = btnIngresar[0]
            Log.d(TAG, "Encontrado botón Ingresar")
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                mostrarToast("Haciendo clic en Ingresar")
                return
            } else {
                node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                mostrarToast("Haciendo clic en Padre de Ingresar")
                return
            }
        }

        // Paso 2: Pantalla de PIN (Buscar campos de PIN y botón Continuar)
        val btnContinuar = rootNode.findAccessibilityNodeInfosByText("Continuar")
        if (btnContinuar.isNotEmpty()) {
            // Rellenar PIN 6880
            // Aquí hay que buscar los EditTexts. Depende de cómo estén estructurados.
            val editTexts = mutableListOf<AccessibilityNodeInfo>()
            findEditTexts(rootNode, editTexts)
            
            if (editTexts.size == 4) {
                Log.d(TAG, "Llenando PIN")
                val pin = listOf("6", "8", "8", "0")
                for (i in 0 until 4) {
                    val arguments = android.os.Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin[i])
                    editTexts[i].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
                btnContinuar[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }

        // Paso 3: Pantalla Principal (Buscar "Ver pendientes" o icono de lista)
        val btnPendientes = rootNode.findAccessibilityNodeInfosByText("Ver pendientes")
        if (btnPendientes.isNotEmpty()) {
            Log.d(TAG, "Entrando a movimientos/pendientes")
            btnPendientes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // Paso 4: Pantalla de Movimientos (Extraer datos y Scrollear)
        // Buscamos si existe el texto "Movimientos" o estamos en la lista
        val txtMovimientos = rootNode.findAccessibilityNodeInfosByText("Movimientos")
        if (txtMovimientos.isNotEmpty()) {
            extraerDatos(rootNode)
            
            // Hacer scroll para actualizar si pasó el tiempo
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                hacerSwipeHaciaAbajo()
                lastScrollTime = currentTime
            }
        }
    }
    
    private fun findEditTexts(node: AccessibilityNodeInfo, outList: MutableList<AccessibilityNodeInfo>) {
        if (node.className == "android.widget.EditText") {
            outList.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findEditTexts(child, outList)
            }
        }
    }

    private fun extraerDatos(node: AccessibilityNodeInfo) {
        val regex = Regex("EVT[A-Z0-9]+") // Ajusta la regex si es necesario
        
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(node, allNodes)
        
        for (i in allNodes.indices) {
            val n = allNodes[i]
            val text = n.text?.toString() ?: ""
            if (regex.containsMatchIn(text)) {
                val codigo = text
                Log.d(TAG, "Código encontrado: $codigo")
                
                // Buscar monto cercano. A menudo es el siguiente nodo de texto
                var monto = "Desconocido"
                for (j in i + 1 until allNodes.size) {
                    val nextText = allNodes[j].text?.toString() ?: ""
                    if (nextText.contains("Bs")) {
                        monto = nextText
                        break
                    }
                }
                
                guardarEnBaseDeDatos(codigo, monto)
            }
        }
    }
    
    private fun findAllNodes(node: AccessibilityNodeInfo, outList: MutableList<AccessibilityNodeInfo>) {
        if (node.text != null) {
            outList.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findAllNodes(child, outList)
            }
        }
    }

    private fun guardarEnBaseDeDatos(codigo: String, monto: String) {
        scope.launch {
            val db = PagoZasDatabase.getDatabase(applicationContext)
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val fechaActual = sdf.format(Date())
            
            val pago = Pago(codigo = codigo, monto = monto, fecha = fechaActual)
            db.pagoDao().insert(pago)
            Log.d(TAG, "Pago guardado/actualizado en BD: $codigo - $monto")
        }
    }

    private fun hacerSwipeHaciaAbajo() {
        val displayMetrics = resources.displayMetrics
        val middleX = displayMetrics.widthPixels / 2f
        val startY = displayMetrics.heightPixels * 0.2f
        val endY = displayMetrics.heightPixels * 0.8f

        val path = Path()
        path.moveTo(middleX, startY)
        path.lineTo(middleX, endY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        
        Log.d(TAG, "Ejecutando Swipe para actualizar...")
        mostrarToast("Actualizando pagos (Swipe)")
        dispatchGesture(gesture, null, null)
    }

    private fun mostrarToast(mensaje: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, mensaje, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {
        Log.e(TAG, "Servicio Interrumpido")
        mostrarToast("Servicio Interrumpido")
    }
}
