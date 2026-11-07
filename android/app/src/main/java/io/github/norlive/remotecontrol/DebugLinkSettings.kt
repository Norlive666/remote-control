package io.github.norlive.remotecontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DebugLinkSettings(vm: RemoteViewModel) {
    var host by rememberSaveable { mutableStateOf(vm.debugHost) }
    var port by rememberSaveable { mutableStateOf(vm.debugPort) }
    var token by rememberSaveable { mutableStateOf(vm.debugToken) }
    var help by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val parsedPort = port.trim().toIntOrNull()
    val validPort = parsedPort != null && parsedPort in 1..65535
    SettingsCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("局域网调试", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (vm.debugMode) "调试链路已启用" else "未启用", fontSize = 12.sp, color = Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text("电脑 IP") }, singleLine = true,
                modifier = Modifier.weight(2f).testTag("debug-host"))
            OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true,
                isError = !validPort, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("debug-port"))
        }
        OutlinedTextField(token, { token = it }, label = { Text("令牌（选填）") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("debug-token"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.updateDebugConfig(host, port, token); vm.connectDebug(); keyboard?.hide()
            }, enabled = host.isNotBlank() && validPort, modifier = Modifier.testTag("debug-connect")) {
                Text(if (vm.debugMode) "重新连接" else "连接调试链路")
            }
            if (vm.debugMode) TextButton({ vm.disconnect() }, modifier = Modifier.testTag("debug-disconnect")) {
                Text("断开")
            }
        }
        if (!validPort) Text("端口请输入 1–65535 的整数", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        Text(vm.debugNote, fontSize = 12.sp, color = Muted, modifier = Modifier.testTag("debug-note"))
    }
    SettingsCard(Modifier.fillMaxWidth()) {
        TextButton({ help = !help }, modifier = Modifier.fillMaxWidth().testTag("debug-help")) {
            Text(if (help) "收起连接说明与帮助  −" else "连接说明与帮助  ＋")
        }
        if (help) {
            Text("手机与电脑需在同一局域网，电脑运行调试桥接器。令牌需与桥接器一致；关闭校验时留空。", fontSize = 12.sp, color = Muted)
            Text("发送与蓝牙相同的 20 字节帧，由电脑经 SWD 写入 g_rc；蓝牙同时发帧时以蓝牙为准。", fontSize = 12.sp, color = Muted)
            Text("写入前请架空机器人，急停仍可用。", fontSize = 12.sp, color = Muted)
        }
    }
}
