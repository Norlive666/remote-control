package io.github.norlive.remotecontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun Bt630ConnectionDialog(vm: RemoteViewModel,scan: ()->Unit,systemBluetooth: ()->Unit,connect: (DeviceRow)->Unit,cancel: ()->Unit,onClose: ()->Unit) {
    var help by remember { mutableStateOf(false) }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            Surface(modifier=Modifier.fillMaxWidth(.84f).height(minOf(maxHeight-24.dp,380.dp)).testTag("bt630-dialog"),shape=MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("BT630 连接",fontSize=18.sp,fontWeight=FontWeight.Bold)
                        TextButton(onClose,modifier=Modifier.testTag("bt630-close")) { Text("关闭") }
                    }
                    Text(vm.status,modifier=Modifier.fillMaxWidth().testTag("bt630-status"),fontSize=12.sp,color=Muted,maxLines=1)
                    Spacer(Modifier.height(8.dp))
                    if(help) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("bt630-diagnostics"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("诊断",fontWeight=FontWeight.Bold)
                            Text("BT630 使用 FFF0 服务、FFF2 写入、FFF1 通知。连接就绪不代表 STM32 已确认收到数据。",fontSize=12.sp)
                            Text("搜索需要附近设备和精确位置权限，并开启手机定位。无需先在系统中配对。",fontSize=12.sp)
                            if(vm.scanDetails.isNotEmpty())Text(vm.scanDetails,fontSize=12.sp)
                            if(vm.connectionError.isNotEmpty())Text("最近错误：${vm.connectionError}",fontSize=12.sp)
                            TextButton(systemBluetooth) { Text("系统蓝牙设置") }
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("bt630-device-list"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            items(vm.devices,key={it.address}) { device ->
                                Row(Modifier.fillMaxWidth().testTag("bt630-device-${device.address}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(device.name,fontSize=13.sp,maxLines=1)
                                        Text(device.address,fontSize=11.sp,color=Muted,maxLines=1)
                                    }
                                    OutlinedButton({connect(device)},
                                        modifier=Modifier.testTag("bt630-connect-${device.address}")) { Text("连接") }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        OutlinedButton({if(vm.scanning)vm.stopScan() else scan()},enabled=!vm.connected && !vm.connecting,
                            modifier=Modifier.testTag("bt630-scan")) { Text(if(vm.scanning)"停止搜索" else "搜索设备") }
                        OutlinedButton({if(vm.connected || vm.connecting)cancel() else onClose()},modifier=Modifier.testTag("bt630-cancel")) {
                            Text(if(vm.connected)"断开" else "取消")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton({help=!help},modifier=Modifier.testTag("bt630-help")) { Text(if(help)"返回列表" else "帮助") }
                    }
                }
            }
        }
    }
}
