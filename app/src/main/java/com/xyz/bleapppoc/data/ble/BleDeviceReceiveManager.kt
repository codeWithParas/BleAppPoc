package com.xyz.bleapppoc.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Message
import android.util.Log
import com.xyz.bleapppoc.data.BleReceiveManager
import com.xyz.bleapppoc.data.BleReceiveResult
import com.xyz.bleapppoc.data.ConnectionState
import com.xyz.bleapppoc.utils.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeoutException
import javax.inject.Inject

@SuppressLint("MissingPermission")
class BleDeviceReceiveManager @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context
) : BleReceiveManager {

    //private val DEVICE_NAME = "BTprinter3600"
    private val DEVICE_NAME = "ET16_NORM_BLE"
    private val TYPE_1_2_SERVICE_UIID = "00001800-0000-1000-8000-00805f9b34fb"
    private val TYPE_1_2_CHARACTERISTICS_UUID = "00002a00-0000-1000-8000-00805f9b34fb"
    //private val TEMP_HUMIDITY_SERVICE_UIID = "0000aa20-0000-1000-8000-00805f9b34fb"
    //private val TEMP_HUMIDITY_CHARACTERISTICS_UUID = "0000aa21-0000-1000-8000-00805f9b34fb"

    override val data: MutableSharedFlow<Resource<BleReceiveResult>> = MutableSharedFlow()

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private var gatt: BluetoothGatt? = null

    private var isScanning = false

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val scanCallback = object : ScanCallback(){
        override fun onScanResult(callbackType: Int, result: ScanResult) {

            if(result.device.name == DEVICE_NAME){
                println("====$$$ BLE DEVICE SCAN NAME    ${result.device.name}")
                println("====$$$ BLE DEVICE SCAN ADDRESS ${result.device.address}")
                if(isScanning){
                    coroutineScope.launch {
                        data.emit(Resource.Loading(message = "Connecting to device..."))
                    }
                    result.device.connectGatt(context,false, gattCallback)
                    isScanning = false
                    bleScanner.stopScan(this)
                }
            }
        }
    }

    private var currentConnectionAttempt = 1
    private var MAXIMUM_CONNECTION_ATTEMPTS = 5

    private val gattCallback = object : BluetoothGattCallback(){
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if(status == BluetoothGatt.GATT_SUCCESS){
                if(newState == BluetoothProfile.STATE_CONNECTED){
                    coroutineScope.launch {
                        data.emit(Resource.Loading(message = "Discovering Services..."))
                    }
                    gatt.discoverServices()
                    this@BleDeviceReceiveManager.gatt = gatt
                } else if(newState == BluetoothProfile.STATE_DISCONNECTED){
                    coroutineScope.launch {
                        data.emit(Resource.Success(data = BleReceiveResult(0f,0f, ConnectionState.Disconnected)))
                    }
                    gatt.close()
                }
            }else{
                gatt.close()
                currentConnectionAttempt+=1
                coroutineScope.launch {
                    data.emit(
                        Resource.Loading(
                            message = "Attempting to connect $currentConnectionAttempt/$MAXIMUM_CONNECTION_ATTEMPTS"
                        )
                    )
                }
                if(currentConnectionAttempt<=MAXIMUM_CONNECTION_ATTEMPTS){
                    startReceiving()
                }else{
                    coroutineScope.launch {
                        data.emit(Resource.Error(errorMessage = "Could not connect to ble device"))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt){
                printGattTable()
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Adjusting MTU space..."))
                }
                gatt.requestMtu(517)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val characteristic = findCharacteristics(TYPE_1_2_SERVICE_UIID, TYPE_1_2_CHARACTERISTICS_UUID)
            if(characteristic == null){
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "Could not find temp and humidity publisher"))
                }
                return
            }
            enableNotification(characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            with(characteristic){
                when(uuid){
                    UUID.fromString(TYPE_1_2_CHARACTERISTICS_UUID) -> {
                        //XX XX XX XX XX XX
                        val multiplicator = if(value.first().toInt()> 0) -1 else 1
                        val type1 = value[1].toInt() + value[2].toInt() / 10f
                        val type2 = value[4].toInt() + value[5].toInt() / 10f
                        val dataResult = BleReceiveResult(
                            multiplicator * type1,
                            type2,
                            ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(
                                Resource.Success(data = dataResult)
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }


    }

    private fun enableNotification(characteristic: BluetoothGattCharacteristic){
        //val cc = "00002a00-0000-1000-8000-00805f9b34fb";
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        val payload = when {
            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> return
        }
        //return issue

        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if(gatt?.setCharacteristicNotification(characteristic, true) == false){
                Log.d("BLEReceiveManager","set characteristics notification failed")
                return
            }
                writeDescription(cccdDescriptor, payload)
        }
    }

    private fun writeDescription(descriptor: BluetoothGattDescriptor, payload: ByteArray){
        gatt?.let { gatt ->
            descriptor.value = payload
            gatt.writeDescriptor(descriptor)
        } ?: error("Not connected to a BLE device!")
    }

    private fun findCharacteristics(serviceUUID: String, characteristicsUUID:String):BluetoothGattCharacteristic?{
        return gatt?.services?.find { service ->
            service.uuid.toString() == serviceUUID
        }?.characteristics?.find { characteristics ->
            characteristics.uuid.toString() == characteristicsUUID
        }
    }



    override fun startReceiving() {
        coroutineScope.launch {
            data.emit(Resource.Loading(message = "Scanning Ble devices..."))
        }
        isScanning = true
        try{
            bleScanner.startScan(null,scanSettings,scanCallback)
        }catch (e: TimeoutException){
            Log.e("BLE", "Timeout during BLE scan", e)
        }
    }

    override fun reconnect() {
        gatt?.connect()
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun closeConnection() {
        bleScanner.stopScan(scanCallback)
        val characteristic = findCharacteristics(TYPE_1_2_SERVICE_UIID, TYPE_1_2_CHARACTERISTICS_UUID)
        if(characteristic != null){
            disconnectCharacteristic(characteristic)
        }
        gatt?.close()
    }

    private fun disconnectCharacteristic(characteristic: BluetoothGattCharacteristic){
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if(gatt?.setCharacteristicNotification(characteristic,false) == false){
                Log.d("TempHumidReceiveManager","set charateristics notification failed")
                return
            }
            writeDescription(cccdDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
    }

}