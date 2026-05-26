package com.example.pagozas.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.example.pagozas.db.Pago
import com.example.pagozas.db.PagoZasDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class ZasAutomatorService : AccessibilityService() {

    companion object {
        var isRunning = false
        const val ZAS_PACKAGE = "bec.vdb.direct"
        const val TAG = "ZasAutomator"

        @Volatile
        private var instance: ZasAutomatorService? = null
        fun getInstance(): ZasAutomatorService? = instance
    }

    // Máquina de estados
    private enum class State {
        IDLE, CLICKING_INGRESAR, FILLING_PIN, NAVIGATING_TO_MOVIMIENTOS, EXTRACTING
    }

    private var state = State.IDLE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    // Regex robusta para EVTA + 8 dígitos (según tus imágenes)
    private val PAT_CODE = Pattern.compile("[A-Z]{2,8}\\d{4,}")
    private val PAT_MONTO = Pattern.compile("\\+?\\s*Bs\\s*[\\d.,]+")

    private var lastScrollTime = 0L
    private val SCROLL_DELAY_MS = 4000L
    private var nextActionAtMs = 0L

    // Popup flotante
    private var windowManager: WindowManager? = null
    private var statusPopup: TextView? = null
    private val hidePopupRunnable = Runnable { hidePopup() }
    private val retryRunnable = Runnable { retryProcess() }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showStatus("PagoZas conectado ✓")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hidePopup()
        mainHandler.removeCallbacksAndMessages(null)
        if (instance == this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        showStatus("Servicio interrumpido")
    }

    // ─── Arrancar automatización ──────────────────────────────────────────────

    fun startAutomation() {
        if (!isRunning) return
        state = State.CLICKING_INGRESAR
        showStatus("Iniciando... abriendo ZA\$")
        launchZas()
        scheduleRetry(1500L)
    }

    private fun launchZas() {
        var intent = packageManager.getLaunchIntentForPackage(ZAS_PACKAGE)
        if (intent == null) {
            intent = Intent(Intent.ACTION_MAIN)
            intent.setClassName(ZAS_PACKAGE, "$ZAS_PACKAGE.MainActivity")
        }
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // ─── Eventos de accesibilidad ─────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning) return
        val pkg = event?.packageName?.toString() ?: ""
        if (pkg != ZAS_PACKAGE) return

        val root = rootInActiveWindow ?: return
        val type = event?.eventType ?: return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            process(root)
        }
    }

    // ─── Máquina de estados ──────────────────────────────────────────────────

    private fun process(root: AccessibilityNodeInfo) {
        if (!isRunning) return
        if (System.currentTimeMillis() < nextActionAtMs) return

        when (state) {
            State.CLICKING_INGRESAR -> handleIngresar(root)
            State.FILLING_PIN       -> handlePin(root)
            State.NAVIGATING_TO_MOVIMIENTOS -> handleNavToMovimientos(root)
            State.EXTRACTING        -> handleExtractAndScroll(root)
            State.IDLE              -> {}
        }
    }

    private fun retryProcess() {
        if (state == State.IDLE) return
        val root = rootInActiveWindow ?: return
        process(root)
    }

    private fun advance(newState: State, delayMs: Long, status: String) {
        state = newState
        nextActionAtMs = System.currentTimeMillis() + delayMs
        showStatus(status)
        scheduleRetry(delayMs)
    }

    private fun scheduleRetry(delayMs: Long) {
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, delayMs + 200L)
    }

    // ─── PASO 1: Hacer clic en "Ingresar" ────────────────────────────────────

    private fun handleIngresar(root: AccessibilityNodeInfo) {
        if (clickByLabels(root, "Ingresar")) {
            advance(State.FILLING_PIN, 1000L, "Ingresado, esperando PIN...")
        } else {
            showStatus("Buscando botón Ingresar...")
            scheduleRetry(800L)
        }
    }

    // ─── PASO 2: Rellenar PIN 6880 ────────────────────────────────────────────

    private fun handlePin(root: AccessibilityNodeInfo) {
        // Verificar si ya pasamos la pantalla del PIN (aparece "Continuar" cuando hay PIN)
        val continuar = findLabel(root, "Continuar")
        if (continuar == null) {
            showStatus("Esperando pantalla de PIN...")
            scheduleRetry(800L)
            return
        }

        // Buscar campos de texto (EditText)
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findEditTexts(root, editTexts)

        val pin = listOf("6", "8", "8", "0")
        if (editTexts.size >= 4) {
            showStatus("Ingresando PIN: 6880")
            for (i in 0 until 4) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin[i])
                editTexts[i].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            Thread.sleep(300)
            performClick(continuar)
            advance(State.NAVIGATING_TO_MOVIMIENTOS, 2000L, "PIN ingresado, navegando...")
        } else {
            // Intentar por click individual en cada dígito del teclado numérico
            showStatus("Buscando campos de PIN (${editTexts.size} encontrados)...")
            scheduleRetry(800L)
        }
    }

    // ─── PASO 3: Ir a Movimientos (ícono de lista / tres rayas) ──────────────

    private fun handleNavToMovimientos(root: AccessibilityNodeInfo) {
        // La pantalla principal muestra "Saldo" y botones. Buscamos el botón de lista/movimientos.
        // Según tus imágenes, hay un ícono de lista (≡) cerca de "Ingresos del día"
        val found = clickByLabels(root,
            "Movimientos", "Ver movimientos", "Historial",
            "Ver pendientes", "Cobro QR ZAS", "Pagos"
        ) || clickByContentDesc(root,
            "Movimientos", "Lista", "Menu", "Menú", "Historial"
        )

        if (found) {
            advance(State.EXTRACTING, 1500L, "Abriendo movimientos...")
        } else {
            // Si vemos "Saldo" ya estamos en el home, intentar el ícono de las 3 rayas
            val saldoNode = findLabel(root, "Saldo")
            if (saldoNode != null) {
                // Buscar el primer botón clickable en la parte superior derecha
                clickTopBarMenuButton(root)
                showStatus("Buscando ícono de movimientos...")
                scheduleRetry(1200L)
            } else {
                showStatus("Esperando pantalla principal...")
                scheduleRetry(1000L)
            }
        }
    }

    private fun clickTopBarMenuButton(root: AccessibilityNodeInfo) {
        // Buscar el botón de menú (≡) que está en la barra superior
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        findClickable(root, clickables)
        val displayHeight = resources.displayMetrics.heightPixels
        val topBar = clickables.filter { getBounds(it).top < displayHeight * 0.2f }
        // Tomar el último elemento de la barra superior (generalmente el menú está a la derecha)
        if (topBar.isNotEmpty()) {
            performClick(topBar.last())
        }
    }

    // ─── PASO 4: Extraer datos y hacer scroll ─────────────────────────────────

    private fun handleExtractAndScroll(root: AccessibilityNodeInfo) {
        // Verificamos que estamos en la pantalla de movimientos
        val enMovimientos = findLabel(root, "Movimientos") != null ||
                            findLabel(root, "COBRO QR ZAS") != null ||
                            findLabel(root, "Cobro QR ZAS") != null

        if (!enMovimientos) {
            showStatus("Esperando pantalla de movimientos...")
            scheduleRetry(1000L)
            return
        }

        // Extraer todos los datos visibles
        extraerDatos(root)

        // Hacer scroll para actualizar
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
            showStatus("Actualizando pagos...")
            hacerSwipeParaActualizar()
            lastScrollTime = currentTime
            scheduleRetry(SCROLL_DELAY_MS)
        }
    }

    // ─── Extracción de datos ─────────────────────────────────────────────────

    private fun extraerDatos(root: AccessibilityNodeInfo) {
        val lines = mutableListOf<String>()
        collectLines(root, lines)

        var codigoEncontrado: String? = null
        var cantidadGuardada = 0

        for (i in lines.indices) {
            val line = lines[i]
            val mc = PAT_CODE.matcher(line)
            if (mc.find()) {
                codigoEncontrado = mc.group()
                continue
            }
            if (codigoEncontrado != null) {
                // Buscar monto en las siguientes líneas
                for (j in i until minOf(i + 5, lines.size)) {
                    val mp = PAT_MONTO.matcher(lines[j])
                    if (mp.find()) {
                        val monto = mp.group().trim()
                        guardarEnBD(codigoEncontrado!!, monto)
                        cantidadGuardada++
                        codigoEncontrado = null
                        break
                    }
                }
            }
        }

        if (cantidadGuardada > 0) {
            showStatus("✓ $cantidadGuardada pago(s) guardado(s)")
        }
    }

    private fun collectLines(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val t = node.text
        if (t != null && t.isNotEmpty()) {
            for (line in t.toString().split("\n")) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) out.add(trimmed)
            }
        }
        for (i in 0 until node.childCount) collectLines(node.getChild(i), out)
    }

    private fun guardarEnBD(codigo: String, monto: String) {
        scope.launch {
            val db = PagoZasDatabase.getDatabase(applicationContext)
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val pago = Pago(codigo = codigo, monto = monto, fecha = sdf.format(Date()))
            db.pagoDao().insert(pago)
            Log.d(TAG, "Guardado: $codigo → $monto")
        }
    }

    // ─── Gesto de swipe para actualizar ──────────────────────────────────────

    private fun hacerSwipeParaActualizar() {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.35f
        val endY   = metrics.heightPixels * 0.75f

        val path = Path()
        path.moveTo(x, startY)
        path.lineTo(x, endY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ─── Helpers de nodos ─────────────────────────────────────────────────────

    /** Busca por texto o contentDescription, sube hasta 5 padres para el click */
    private fun clickByLabels(root: AccessibilityNodeInfo, vararg labels: String): Boolean {
        for (label in labels) {
            val node = findLabel(root, label)
            if (node != null && performClick(node)) return true
        }
        return false
    }

    private fun clickByContentDesc(root: AccessibilityNodeInfo, vararg descs: String): Boolean {
        for (desc in descs) {
            val node = findByContentDesc(root, desc)
            if (node != null && performClick(node)) return true
        }
        return false
    }

    /** Sube hasta 5 niveles buscando un nodo clickable */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        for (d in 0 until 5) {
            if (cur != null && cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur?.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findLabel(n: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (n == null) return null
        if (matchesText(n, label)) return n
        for (i in 0 until n.childCount) {
            val f = findLabel(n.getChild(i), label)
            if (f != null) return f
        }
        return null
    }

    private fun findByContentDesc(n: AccessibilityNodeInfo?, desc: String): AccessibilityNodeInfo? {
        if (n == null) return null
        val cd = n.contentDescription?.toString() ?: ""
        if (cn(cd, desc)) return n
        for (i in 0 until n.childCount) {
            val f = findByContentDesc(n.getChild(i), desc)
            if (f != null) return f
        }
        return null
    }

    private fun findEditTexts(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isEditable || node.className?.contains("EditText") == true) out.add(node)
        for (i in 0 until node.childCount) findEditTexts(node.getChild(i), out)
    }

    private fun findClickable(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) findClickable(node.getChild(i), out)
    }

    private fun matchesText(n: AccessibilityNodeInfo, kw: String): Boolean {
        return cn(n.text?.toString() ?: "", kw) ||
               cn(n.contentDescription?.toString() ?: "", kw)
    }

    private fun cn(src: String, needle: String): Boolean = norm(src).contains(norm(needle))
    private fun norm(v: String) = v.lowercase()
        .replace("á","a").replace("é","e").replace("í","i")
        .replace("ó","o").replace("ú","u").replace("ñ","n")
        .replace("\\s+".toRegex(), " ").trim()

    private fun getBounds(n: AccessibilityNodeInfo): Rect {
        val r = Rect(); n.getBoundsInScreen(r); return r
    }

    // ─── Popup flotante de estado ─────────────────────────────────────────────

    private fun showStatus(msg: String) {
        Log.i(TAG, msg)
        showPopup(msg)
    }

    private fun showPopup(message: String) {
        mainHandler.post {
            val wm = windowManager ?: return@post
            if (statusPopup == null) {
                statusPopup = TextView(this).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(36, 20, 36, 20)
                    setBackgroundColor(0xE8111111.toInt())
                    gravity = Gravity.CENTER
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = 100
                }
                wm.addView(statusPopup, params)
            }
            statusPopup?.visibility = View.VISIBLE
            statusPopup?.text = message
            mainHandler.removeCallbacks(hidePopupRunnable)
            mainHandler.postDelayed(hidePopupRunnable, 2500L)
        }
    }

    private fun hidePopup() {
        mainHandler.post {
            statusPopup?.let {
                try { windowManager?.removeView(it) } catch (e: Exception) { /* ignorar */ }
                statusPopup = null
            }
        }
    }
}
