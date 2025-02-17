package com.xyz.bleapppoc.data

import com.xyz.bleapppoc.utils.Resource
import dagger.Provides
import kotlinx.coroutines.flow.MutableSharedFlow

interface BleReceiveManager {

    val data: MutableSharedFlow<Resource<BleReceiveResult>>

    fun reconnect()

    fun disconnect()

    fun startReceiving()

    fun closeConnection()
}