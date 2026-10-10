package io.github.norlive.remotecontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun SliderSettings(vm: RemoteViewModel) {
    vm.sliderConfigs.forEachIndexed { index,config ->
        var stepped by remember(config) { mutableStateOf(config.stepped) }
        var values by remember(config) { mutableStateOf(config.values.map(Int::toString)) }
        val parsed=values.map(String::toIntOrNull)
        val valid=parsed.all { it!=null && it in 0..1000 }
        Text("滑块 ${index+1}",fontWeight=FontWeight.Bold)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(stepped,{stepped=true},label={Text("分档")},modifier=Modifier.testTag("slider-stepped-$index"))
            FilterChip(!stepped,{stepped=false},label={Text("连续")},modifier=Modifier.testTag("slider-continuous-$index"))
        }
        if(stepped) {
            Text("${values.size} 档 · 每档发送实际数值",fontSize=12.sp)
            Slider(values.size.toFloat(),{raw ->
                val count=raw.roundToInt()
                values=List(count) { i -> values.getOrNull(i) ?: i.toString() }
            },valueRange=2f..8f,steps=5,modifier=Modifier.testTag("slider-count-$index"))
            values.chunked(2).forEachIndexed { row,entries ->
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    entries.forEachIndexed { column,text ->
                        val slot=row*2+column
                        OutlinedTextField(text,{new -> values=values.mapIndexed { i,v -> if(i==slot)new else v }},
                            label={Text("第 ${slot+1} 档（0～1000）")},singleLine=true,
                            isError=parsed[slot]==null || parsed[slot] !in 0..1000,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                            modifier=Modifier.weight(1f).testTag("slider-value-$index-$slot"))
                    }
                    if(entries.size==1)Spacer(Modifier.weight(1f))
                }
            }
        }
        OutlinedButton({vm.setSliderConfig(index,SliderConfig(stepped,if(stepped)parsed.map { it!! } else config.values))},
            enabled=!stepped || valid,modifier=Modifier.testTag("slider-apply-$index")){Text("应用滑块设置")}
        HorizontalDivider()
    }
}
