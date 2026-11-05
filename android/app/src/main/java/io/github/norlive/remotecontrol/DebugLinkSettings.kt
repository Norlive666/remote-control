package io.github.norlive.remotecontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设置页里的“调试链路”入口：电脑上的 tune.py 收帧，经 SWD 写进 g_rc。 */
@Composable
internal fun DebugLinkSettings(vm: RemoteViewModel) {
    var host by remember { mutableStateOf(vm.debugHost) }
    var port by remember { mutableStateOf(vm.debugPort) }
    var token by remember { mutableStateOf(vm.debugToken) }
    HorizontalDivider()
    Text("调试链路（局域网 → SWD）", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(host, { host = it }, label = { Text("电脑 IP") }, singleLine = true,
            modifier = Modifier.weight(2f).testTag("debug-host"))
        OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("debug-port"))
    }
    OutlinedTextField(token, { token = it }, label = { Text("令牌（桥接器关闭校验时留空）") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("debug-token"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button({ vm.updateDebugConfig(host, port, token); vm.connectDebug() },
            enabled = host.isNotBlank(), modifier = Modifier.testTag("debug-connect")) {
            Text(if (vm.debugMode) "重新连接" else "连接调试链路")
        }
        if (vm.debugMode) TextButton({ vm.disconnect() }, modifier = Modifier.testTag("debug-disconnect")) { Text("断开") }
    }
    Text(vm.debugNote, fontSize = 11.sp, color = Muted, modifier = Modifier.testTag("debug-note"))
    Column {
        Text("与蓝牙发送同一份 20 字节帧，由电脑写进 g_rc。", fontSize = 11.sp, color = Muted)
        Text("蓝牙同时发帧时以蓝牙为准；写入前请架空机器人，急停仍可用。", fontSize = 11.sp, color = Muted)
    }
}