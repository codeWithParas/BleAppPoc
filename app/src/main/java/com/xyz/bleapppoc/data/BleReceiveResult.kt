package com.xyz.bleapppoc.data

data class BleReceiveResult(
    val type1Data: Float?,
    val type2Data: Float?,
    //val deviceName: String? = "",
    //val deviceAddress: String? = "",
    val connectionState: ConnectionState
)
