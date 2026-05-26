package com.example.pagozas

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pagozas.db.PagoZasDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PagosViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = PagoZasDatabase.getDatabase(app).pagoDao()

    val pagos = dao.getAllPagos()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun clearAll() {
        viewModelScope.launch { dao.deleteAll() }
    }
}
