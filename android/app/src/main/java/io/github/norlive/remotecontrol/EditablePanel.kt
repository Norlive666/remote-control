package io.github.norlive.remotecontrol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun EditablePanel(vm: RemoteViewModel,action: (String)->Unit) {
    BackHandler(enabled=vm.layoutEditing) { vm.cancelLayoutEdit() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Surface).safeDrawingPadding()) {
        val compact=maxHeight<370.dp
        Column(Modifier.fillMaxSize().padding(horizontal=if(compact)20.dp else 28.dp,vertical=if(compact)8.dp else 16.dp)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("panel-canvas")) {
                val width=maxWidth;val height=maxHeight
                val header=if(compact)66.dp else 82.dp
                val sliderHeight=64.dp
                val enableWidth=minOf(88.dp,width/5)
                val gap=if(compact)20.dp else 32.dp
                val channelWidth=((width-enableWidth-gap*2)/2).coerceAtLeast(1.dp)
                val rightX=width-channelWidth
                val stickSide=minOf(260.dp,channelWidth,(height-header-sliderHeight-30.dp).coerceAtLeast(1.dp))
                val stickHeight=stickSide+22.dp
                val stickY=height-stickHeight
                val state=vm.controls
                val active=!state.stopped && !vm.layoutEditing
                val keyWidth=if(compact)56.dp else 64.dp
                val keyHeight=minOf(60.dp,height)
                val density=LocalDensity.current
                val canvasPx=with(density){Offset(width.toPx(),height.toPx())}

                Column(Modifier.width((width-keyWidth*4-24.dp).coerceAtLeast(80.dp))) {
                    Text("ROBOT CONTROL",fontSize=10.sp,color=Muted,letterSpacing=2.sp)
                    Text(if(vm.layoutEditing)"编辑布局" else "操控面板",fontSize=if(compact)19.sp else 23.sp,fontWeight=FontWeight.Medium)
                    Text(if(vm.layoutEditing)"使能已关闭" else vm.status,fontSize=11.sp,color=Muted,maxLines=1)
                }
                vm.keys.forEachIndexed { i,key ->
                    if(key.action!="hide") {
                        PositionedItem(vm,"key$i",width-keyWidth*(4-i)-6.dp*(3-i),0.dp,keyWidth,keyHeight,canvasPx) {
                            KeyButton(key.label,key.action,key.action=="stop",enabled=!vm.layoutEditing) { action(key.action) }
                        }
                    }
                }
                PositionedItem(vm,"leftSlider",0.dp,header,channelWidth,sliderHeight,canvasPx) {
                    SliderControl("滑块 1",state.slider1,active){vm.slider(0,it)}
                }
                PositionedItem(vm,"rightSlider",rightX,header,channelWidth,sliderHeight,canvasPx) {
                    SliderControl("滑块 2",state.slider2,active){vm.slider(1,it)}
                }
                PositionedItem(vm,"leftStick",(channelWidth-stickSide)/2,stickY,stickSide,stickHeight,canvasPx) {
                    StickControl("左摇杆",state.lx,state.ly,active){x,y->vm.axes(true,x,y)}
                }
                PositionedItem(vm,"rightStick",rightX+(channelWidth-stickSide)/2,stickY,stickSide,stickHeight,canvasPx) {
                    StickControl("右摇杆",state.rx,state.ry,active){x,y->vm.axes(false,x,y)}
                }
                val enableHeight=minOf(130.dp,(height-header).coerceAtLeast(1.dp))
                PositionedItem(vm,"enable",(width-enableWidth)/2,header+(height-header-enableHeight)/2,enableWidth,enableHeight,canvasPx) {
                    Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceEvenly) {
                        Text(if(vm.mode==LinkMode.HC05)"HC-05" else "FSC-BT630",fontSize=12.sp,fontWeight=FontWeight.Medium,color=Muted)
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            Switch(state.enabled,{vm.enable(it)},enabled=vm.connected && !vm.layoutEditing)
                            Text(if(state.stopped)"急停锁定" else "使能",fontSize=12.sp,color=Ink)
                        }
                        Text(if(vm.mode==LinkMode.HC05)"20 Hz" else "50 Hz",fontSize=11.sp,color=Muted)
                    }
                }
                // Keep the settings escape route above movable items.
                Box(Modifier.align(Alignment.TopEnd).size(keyWidth,keyHeight)) {
                    KeyButton("设置","settings",enabled=!vm.layoutEditing){action("settings")}
                }
            }
            HorizontalDivider(color=Line)
            Row(Modifier.fillMaxWidth().height(46.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                if(vm.layoutEditing) {
                    Text("拖动带框控件",fontSize=12.sp,color=Muted)
                    Row {
                        TextButton({vm.cancelLayoutEdit()}){Text("取消")}
                        TextButton({vm.defaultLayoutDraft()}){Text("恢复默认")}
                        TextButton({vm.saveLayoutEdit()}){Text("保存")}
                    }
                } else {
                    Text(vm.mode.title,fontSize=11.sp,color=Muted)
                    Text("已提交 ${vm.sent} 帧 · ${if(vm.controls.enabled)"使能开启" else "使能关闭"}",fontSize=11.sp,color=Muted)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.PositionedItem(vm: RemoteViewModel,id: String,defaultX: Dp,defaultY: Dp,width: Dp,height: Dp,canvas: Offset,content: @Composable ()->Unit) {
    val density=LocalDensity.current
    val item=with(density){Offset(width.toPx(),height.toPx())}
    val default=with(density){PanelPosition(
        if(canvas.x>item.x)(defaultX.toPx()/(canvas.x-item.x)).coerceIn(0f,1f)else 0f,
        if(canvas.y>item.y)(defaultY.toPx()/(canvas.y-item.y)).coerceIn(0f,1f)else 0f)}
    val position=vm.layoutPositions[id] ?: default
    val offset=panelOffset(position,canvas.x,canvas.y,item.x,item.y)
    val move by rememberUpdatedState<(Offset)->Unit> { delta ->
        val current=vm.layoutPositions[id] ?: default
        vm.movePanel(id,movePanelItem(current,delta.x,delta.y,canvas.x,canvas.y,item.x,item.y))
    }
    Box(Modifier.offset{IntOffset(offset.x.roundToInt(),offset.y.roundToInt())}.size(width,height)
        .testTag("move-$id")
        .then(if(vm.layoutEditing)Modifier.border(2.dp,Accent,RoundedCornerShape(8.dp)) else Modifier)
        .pointerInput(id,vm.layoutEditing,canvas,item) {
            if(vm.layoutEditing)detectDragGestures { change,delta -> change.consume();move(delta) }
        },contentAlignment=Alignment.Center) { content() }
}

@Composable
private fun SliderControl(title: String,value: Int,active: Boolean,onChange: (Int)->Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(title,fontSize=12.sp);Text("${value/10}",fontSize=12.sp,color=Accent,fontFamily=FontFamily.Monospace)
        }
        Slider(value.toFloat(),{onChange(it.roundToInt())},valueRange=0f..1000f,enabled=active,modifier=Modifier.testTag(title))
    }
}

@Composable
private fun StickControl(title: String,x: Int,y: Int,active: Boolean,onMove: (Int,Int)->Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(22.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(title,fontSize=12.sp)
            Text("X $x  Y $y",fontSize=10.sp,color=Muted,fontFamily=FontFamily.Monospace)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { Joystick(title,x,y,active,onMove) }
    }
}
