package com.xyz.bleapppoc.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyz.bleapppoc.utils.Resource
import com.xyz.bleapppoc.data.BleReceiveManager
import com.xyz.bleapppoc.data.ConnectionState
import dagger.Provides
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BleDataViewModel @Inject constructor(
    private val bleReceiveManager: BleReceiveManager
) : ViewModel(){

    var intialisingMessage by mutableStateOf<String?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var type1Data by mutableFloatStateOf(0f)
        private set

    var type2Data by mutableFloatStateOf(0f)
        private set

    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Uninitialized)

    private fun subscribeToChanges(){
        viewModelScope.launch {
            bleReceiveManager.data.collect{ result ->
                when(result){
                    is Resource.Success -> {
                        connectionState = result.data.connectionState
                        type1Data = result.data.type1Data ?: 0f
                        type2Data = result.data.type2Data ?: 0f
                    }

                    is Resource.Loading -> {
                        intialisingMessage = result.message
                        connectionState = ConnectionState.CurrentlyInitializing
                    }

                    is Resource.Error -> {
                        errorMessage = result.errorMessage
                        connectionState = ConnectionState.Uninitialized
                    }
                }
            }
        }
    }

    fun disconnect(){
        bleReceiveManager.disconnect()
    }

    fun reconnect(){
        bleReceiveManager.reconnect()
    }

    fun initializeConnection(){
        errorMessage = null
        subscribeToChanges()
        bleReceiveManager.startReceiving()
    }

    override fun onCleared() {
        super.onCleared()
        bleReceiveManager.closeConnection()
    }
}