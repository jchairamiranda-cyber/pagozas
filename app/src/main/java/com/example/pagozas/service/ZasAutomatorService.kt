package com.example.pagozas.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
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

    private enum class State {
        IDLE, CLICKING_INGRESAR, FILLING_PIN, NAVIGATING_TO_MOVIMIENTOS, EXTRACTING
    }

    private var state = State.IDLE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    private val PAT_CODE  = Pattern.compile("[A-Z]{2,8}\\d{4,}")
    private val PAT_MONTO = Pattern.compile("\\+?\\s*Bs\\s*[\\d.,]+")

    private var lastScrollTime = 0L
    private val SCROLL_DELAY_MS = 4000L
    private var nextActionAtMs = 0L
    private var pinAttempts = 0

    private var windowManager: WindowManager? = null
    private var statusPopup: TextView? = null
    private val hidePopupRunnable = Runnable { hidePopup() }
    private val retryRunnable    = Runnable { retryProcess() }

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

    override fun onInterrupt() { showStatus("Servicio interrumpido") }

    // ─── Arrancar automatización ──────────────────────────────────────────────

    fun startAutomation() {
        if (!isRunning) return
        pinAttempts = 0
        state = State.CLICKING_INGRESAR
        showStatus("Iniciando... abriendo ZA\$")
        launchZas()
        scheduleRetry(2000L) // 2 segundos para que cargue la pantalla antes de actuar
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
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED           ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED          ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            process(root)
        }
    }

    // ─── Máquina de estados ──────────────────────────────────────────────────

    private fun process(root: AccessibilityNodeInfo) {
        if (!isRunning) return
        if (System.currentTimeMillis() < nextActionAtMs) return
        when (state) {
            State.CLICKING_INGRESAR          -> handleIngresar(root)
            State.FILLING_PIN                -> handlePin(root)
            State.NAVIGATING_TO_MOVIMIENTOS  -> handleNavToMovimientos(root)
            State.EXTRACTING                 -> handleExtractAndScroll(root)
            State.IDLE                       -> {}
        }
    }

    private fun retryProcess() {
        if (state == State.IDLE) return
        process(rootInActiveWindow ?: return)
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
        showStatus("Esperando pantalla de inicio...")
        if (clickByLabels(root, "Ingresar", "Entrar", "Login", "Acceder")) {
            advance(State.FILLING_PIN, 1200L, "Clic en Ingresar ✓ — esperando PIN...")
        } else {
            val fields = mutableListOf<AccessibilityNodeInfo>()
            findEditTexts(root, fields)
            if (fields.isNotEmpty()) {
                advance(State.FILLING_PIN, 400L, "Pantalla de PIN detectada")
            } else {
                scheduleRetry(800L)
            }
        }
    }

    // ─── PASO 2: Rellenar PIN ─────────────────────────────────────────────────

    private fun handlePin(root: AccessibilityNodeInfo) {
        val continuar = findLabel(root, "Continuar")
            ?: findLabel(root, "Aceptar")
            ?: findLabel(root, "OK")

        if (continuar == null) {
            showStatus("Esperando pantalla de PIN... (intento $pinAttempts)")
            pinAttempts++
            scheduleRetry(800L)
            return
        }

        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findEditTexts(root, editTexts)

        val pin = listOf("6", "8", "8", "0")
        if (editTexts.size >= 4) {
            showStatus("Ingresando PIN...")
            for (i in 0 until 4) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin[i])
                editTexts[i].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Thread.sleep(80)
            }
            Thread.sleep(300)
            performClick(continuar)
            advance(State.NAVIGATING_TO_MOVIMIENTOS, 2000L, "PIN ingresado ✓ — navegando al menú...")
        } else if (editTexts.size == 1) {
            // Campo único de PIN
            showStatus("Ingresando PIN en campo único...")
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "6880")
            editTexts[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Thread.sleep(300)
            performClick(continuar)
            advance(State.NAVIGATING_TO_MOVIMIENTOS, 2000L, "PIN ingresado ✓ — navegando al menú...")
        } else {
            showStatus("Buscando campos PIN (${editTexts.size} encontrados)...")
            scheduleRetry(800L)
        }
    }

    // ─── PASO 3: Ir a Movimientos ─────────────────────────────────────────────

    private fun handleNavToMovimientos(root: AccessibilityNodeInfo) {
        showStatus("Buscando ícono ≡ de movimientos...")

        // 1. Intentar por label/contentDesc directo
        val found = clickByLabels(root,
            "Movimientos", "Ver movimientos", "Historial",
            "Ver pendientes", "Cobro QR ZAS", "Pagos", "Cobros"
        ) || clickByContentDesc(root,
            "Movimientos", "Lista", "Historial"
        )

        if (found) {
            advance(State.EXTRACTING, 1500L, "Abriendo movimientos...")
            return
        }

        // 2. Estamos en el home (hay nodo "Saldo") → buscar el botón ≡ en la
        //    zona media de la tarjeta de saldo (NO los 3 puntos de arriba)
        val saldoNode = findLabel(root, "Saldo")
        if (saldoNode != null) {
            if (clickSaldoCardListIcon(root)) {
                advance(State.EXTRACTING, 1500L, "Clic en ≡ — abriendo movimientos...")
            } else {
                showStatus("Buscando ≡ en tarjeta de saldo...")
                scheduleRetry(1000L)
            }
        } else {
            showStatus("Esperando pantalla principal...")
            scheduleRetry(1000L)
        }
    }

    /**
     * Hace clic en el botón ≡ (lista/movimientos) que está DENTRO de la tarjeta
     * de saldo, en la zona media de pantalla — izquierda del centro.
     * Evita los 3 puntos (⋮) que están en la barra superior.
     */
    private fun clickSaldoCardListIcon(root: AccessibilityNodeInfo): Boolean {
        val h = resources.displayMetrics.heightPixels
        val w = resources.displayMetrics.widthPixels

        val clickables = mutableListOf<AccessibilityNodeInfo>()
        findClickable(root, clickables)

        // El ≡ está en la franja vertical 42-73 % de pantalla, mitad izquierda
        val candidates = clickables
            .filter { n ->
                val b = getBounds(n)
                b.top  >  h * 0.42f &&
                b.bottom < h * 0.74f &&
                b.centerX() < w * 0.55f
            }
            .sortedBy { getBounds(it).top }

        if (candidates.isNotEmpty()) {
            Log.d(TAG, "clickSaldoCardListIcon: ${candidates.size} candidatos, tomando el primero")
            return performClick(candidates.first())
        }
        return false
    }

    // ─── PASO 4: Extraer datos y hacer scroll ─────────────────────────────────

    private fun handleExtractAndScroll(root: AccessibilityNodeInfo) {
        val enMovimientos = findLabel(root, "Movimientos") != null ||
                            findLabel(root, "COBRO QR ZAS") != null ||
                            findLabel(root, "Cobro QR ZAS") != null

        if (!enMovimientos) {
            showStatus("Esperando pantalla de movimientos...")
            scheduleRetry(1000L)
            return
        }

        val antes = System.currentTimeMillis()
        val guardados = extraerDatos(root)
        if (guardados > 0) {
            showStatus("✓ $guardados pago(s) registrado(s)")
        } else {
            showStatus("Escaneando pagos en pantalla...")
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
            showStatus("Actualizando lista de pagos...")
            hacerSwipeParaActualizar()
            lastScrollTime = currentTime
            scheduleRetry(SCROLL_DELAY_MS)
        }
    }

    // ─── Extracción de datos ─────────────────────────────────────────────────

    private fun extraerDatos(root: AccessibilityNodeInfo): Int {
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
        return cantidadGuardada
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
        val x      = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.35f
        val endY   = metrics.heightPixels * 0.75f
        val path   = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ─── Helpers de nodos ─────────────────────────────────────────────────────

    /** Intenta hacer clic buscando por texto/contentDescription, normalizado y sin tildes. */
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

    /** Sube hasta 5 niveles buscando un padre clickable antes de forzar el click directo. */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        for (d in 0 until 5) {
            if (cur != null && cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur?.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Busca un nodo cuyo texto, contentDescription o hint contenga el label (normalizado). */
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
        if (cn(n.contentDescription?.toString() ?: "", desc)) return n
        for (i in 0 until n.childCount) {
            val f = findByContentDesc(n.getChild(i), desc)
            if (f != null) return f
        }
        return null
    }

    /** Busca un nodo clickable/focusable que contenga alguna de las keywords. */
    private fun findClickableNodeByKeywords(root: AccessibilityNodeInfo?, vararg kws: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (matchesAny(root, *kws) && (root.isClickable || root.isFocusable)) return root
        for (i in 0 until root.childCount) {
            val f = findClickableNodeByKeywords(root.getChild(i), *kws)
            if (f != null) return f
        }
        return null
    }

    /** Busca un nodo por su nombre de clase exacto. */
    private fun findClass(n: AccessibilityNodeInfo?, cls: String): AccessibilityNodeInfo? {
        if (n == null) return null
        if (safe(n.className) == cls) return n
        for (i in 0 until n.childCount) {
            val f = findClass(n.getChild(i), cls)
            if (f != null) return f
        }
        return null
    }

    /** Recoge todos los EditText ordenados por posición vertical. */
    private fun collectEditableFields(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val fields = mutableListOf<AccessibilityNodeInfo>()
        findEditTexts(root, fields)
        fields.sortBy { getBounds(it).top }
        return fields
    }

    /** Busca un EditText cuyo label/hint/id contenga alguna de las keywords. */
    private fun findFieldByKeywords(root: AccessibilityNodeInfo, vararg kws: String): AccessibilityNodeInfo? {
        for (f in collectEditableFields(root)) {
            if (matchesAny(f, *kws)) return f
            if (matchesAny(f.parent, *kws)) return f
        }
        return null
    }

    /** Hace clic en el nodo clickable número [index] contando desde la parte inferior de pantalla. */
    private fun clickBottom(root: AccessibilityNodeInfo, index: Int): Boolean {
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        findClickable(root, clickables)
        val minTop = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        val bottom = clickables.filter { getBounds(it).top >= minTop }
            .sortedWith(compareBy({ getBounds(it).top }, { getBounds(it).left }))
        return bottom.size > index && performClick(bottom[index])
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

    /** Recoge todos los nodos cuyo className contiene [cls]. */
    private fun collectByClass(n: AccessibilityNodeInfo?, cls: String, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        if (safe(n.className).contains(cls)) out.add(n)
        for (i in 0 until n.childCount) collectByClass(n.getChild(i), cls, out)
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val f = findScrollable(n.getChild(i))
            if (f != null) return f
        }
        return null
    }

    /** Comprueba texto, contentDescription, hint (API 26+) e ID de recurso. */
    private fun matchesText(n: AccessibilityNodeInfo, kw: String): Boolean =
        cn(safe(n.text), kw) ||
        cn(safe(n.contentDescription), kw) ||
        cn(getHint(n), kw)

    /** True si el nodo cumple alguna keyword en texto, contentDesc, hint o viewId. */
    private fun matchesAny(n: AccessibilityNodeInfo?, vararg kws: String): Boolean {
        if (n == null) return false
        for (kw in kws) {
            if (matchesText(n, kw) || cn(safe(n.viewIdResourceName), kw)) return true
        }
        return false
    }

    private fun cn(src: String, needle: String): Boolean = norm(src).contains(norm(needle))

    private fun norm(v: String) = v.lowercase()
        .replace("á","a").replace("é","e").replace("í","i")
        .replace("ó","o").replace("ú","u").replace("ñ","n")
        .replace("\\s+".toRegex(), " ").trim()

    private fun safe(v: CharSequence?): String = v?.toString() ?: ""

    private fun getHint(n: AccessibilityNodeInfo?): String {
        if (n == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ""
        return safe(n.hintText)
    }

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
