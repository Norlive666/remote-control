package io.github.norlive.remotecontrol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
        val gap=if(compact)12.dp else 20.dp
        Column(Modifier.fillMaxSize().padding(horizontal=if(compact)10.dp else 16.dp,vertical=8.dp)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("panel-canvas")) {
                val width=maxWidth;val height=maxHeight
                val sliderHeight=if(compact)54.dp else 60.dp
                val optionWidth=100.dp
                val sliderWidth=((width-optionWidth-gap*2)/2).coerceAtLeast(1.dp)
                val mainY=sliderHeight+10.dp
                val columnWidth=((width-gap*2)/3).coerceAtLeast(1.dp)
                val middleX=columnWidth+gap
                val rightX=width-columnWidth
                val mainHeight=(height-mainY).coerceAtLeast(1.dp)
                val hubHeight=minOf(if(compact)112.dp else 128.dp,(mainHeight-92.dp).coerceAtLeast(1.dp))
                val hubY=mainY+44.dp+(mainHeight-44.dp-hubHeight-52.dp).coerceAtLeast(0.dp)/2
                val state=vm.controls
                val active=!state.stopped && !vm.layoutEditing
                val density=LocalDensity.current
                val canvasPx=with(density){Offset(width.toPx(),height.toPx())}
                PositionedItem(vm,"leftSlider",0.dp,0.dp,sliderWidth,sliderHeight,canvasPx) {
                    SliderControl("滑块 1",state.slider1,active,vm.sliderConfigs[0]){vm.slider(0,it)}
                }
                PositionedItem(vm,"rightSlider",width-sliderWidth,0.dp,sliderWidth,sliderHeight,canvasPx) {
                    SliderControl("滑块 2",state.slider2,active,vm.sliderConfigs[1]){vm.slider(1,it)}
                }
                PositionedItem(vm,"leftStick",0.dp,mainY,columnWidth,mainHeight,canvasPx) {
                    StickControl(vm,"左摇杆",0,state.lx,state.ly,active,vm.stickConfigs[0],{vm.cycleStickMode(0)}){x,y->vm.axes(true,x,y)}
                }
                PositionedItem(vm,"rightStick",rightX,mainY,columnWidth,mainHeight,canvasPx) {
                    StickControl(vm,"右摇杆",1,state.rx,state.ry,active,vm.stickConfigs[1],{vm.cycleStickMode(1)}){x,y->vm.axes(false,x,y)}
                }
                Column(Modifier.offset(x=middleX,y=mainY).width(columnWidth),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("ROBOT CONTROL",fontSize=9.sp,color=Muted,letterSpacing=1.sp,fontWeight=FontWeight.Bold)
                    Text(if(vm.layoutEditing)"编辑布局" else "操控面板",fontSize=18.sp,fontWeight=FontWeight.Bold)
                }
                PositionedItem(vm,"enable",middleX,hubY,columnWidth,hubHeight,canvasPx) {
                    BluetoothHub(vm){action("connect")}
                }
                // Keep the fixed entry above movable controls so layout recovery is always reachable.
                OutlinedButton({action("settings")},enabled=!vm.layoutEditing,shape=RoundedCornerShape(16.dp),
                    contentPadding=PaddingValues(8.dp),colors=ButtonDefaults.outlinedButtonColors(containerColor=Color.White,contentColor=Ink),
                    modifier=Modifier.align(Alignment.TopCenter).padding(top=6.dp).size(optionWidth,44.dp).testTag("panel-settings")) {
                    Icon(Icons.Outlined.Tune,null,Modifier.size(16.dp),tint=Accent)
                    Spacer(Modifier.width(5.dp));Text("功能选项",fontSize=11.sp,maxLines=1)
                }
            }
            if(vm.layoutEditing) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(44.dp).softCard().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    var expanded by remember { mutableStateOf(false) }
                    val names=mapOf("leftPad" to "左蓝色摇杆","rightPad" to "右蓝色摇杆","leftStick" to "左摇杆卡片","rightStick" to "右摇杆卡片","leftSlider" to "滑块 1","rightSlider" to "滑块 2","enable" to "蓝牙卡片")
                    Box {
                        TextButton({expanded=true},contentPadding=PaddingValues(3.dp)){Text("${names[vm.selectedLayoutId]} ▾",fontSize=10.sp)}
                        DropdownMenu(expanded,{expanded=false}) { names.forEach { (id,name)->DropdownMenuItem(text={Text(name)},onClick={vm.selectPanel(id);expanded=false}) } }
                    }
                    Slider(vm.layoutScales[vm.selectedLayoutId] ?: 1f,{vm.resizePanel(it)},valueRange=.5f..1.5f,modifier=Modifier.weight(1f).height(32.dp).testTag("layout-size"))
                    Text("${((vm.layoutScales[vm.selectedLayoutId] ?: 1f)*100).roundToInt()}%",fontSize=10.sp)
                    Row {
                        TextButton({vm.cancelLayoutEdit()}){Text("取消")}
                        TextButton({vm.defaultLayoutDraft()}){Text("恢复默认")}
                        TextButton({vm.saveLayoutEdit()}){Text("保存")}
                    }
                }
            }
        }
    }
}

private fun Modifier.softCard(): Modifier {
    val shape=RoundedCornerShape(20.dp)
    return shadow(2.dp,shape).background(Color.White,shape).border(1.dp,Line,shape)
}

@Composable
private fun BluetoothHub(vm: RemoteViewModel,onConnect: ()->Unit) {
    Column(Modifier.fillMaxSize().softCard().padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween) {
        FilledTonalButton(onConnect,enabled=!vm.layoutEditing,shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=3.dp),
            colors=ButtonDefaults.filledTonalButtonColors(containerColor=Color(0xffe1f5fe),contentColor=Accent),modifier=Modifier.fillMaxWidth().height(38.dp)) {
            Icon(Icons.Outlined.Bluetooth,null,Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(if(vm.mode==LinkMode.HC05)"HC-05" else "FSC-BT630",fontSize=11.sp,lineHeight=12.sp,fontWeight=FontWeight.Bold)
                Text(if(vm.connected)"点击查看连接" else "点击配对连接",fontSize=9.sp,lineHeight=10.sp,color=Muted)
            }
        }
        Text(if(vm.layoutEditing)"使能已关闭" else vm.status,fontSize=10.sp,color=Muted,maxLines=1)
        HorizontalDivider(color=Line)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Switch(vm.controls.enabled,{vm.enable(it)},enabled=vm.connected && !vm.layoutEditing,modifier=Modifier.height(32.dp))
                Text("使能",fontSize=10.sp,color=Ink)
            }
            Text(if(vm.mode==LinkMode.HC05)"20 Hz" else "50 Hz",fontSize=10.sp,color=Muted,fontFamily=FontFamily.Monospace)
        }
    }
}

@Composable
private fun BoxScope.PositionedItem(vm: RemoteViewModel,id: String,defaultX: Dp,defaultY: Dp,width: Dp,height: Dp,canvas: Offset,content: @Composable ()->Unit) {
    val density=LocalDensity.current
    val scale=vm.layoutScales[id] ?: 1f
    val canvasWidth=with(density){canvas.x.toDp()};val canvasHeight=with(density){canvas.y.toDp()}
    val actualWidth=(width*scale).coerceIn(minOf(width,160.dp,canvasWidth),canvasWidth)
    val minimumHeight=if(id.endsWith("Slider"))54.dp else if(id=="enable")100.dp else 150.dp
    val actualHeight=(height*scale).coerceIn(minOf(height,minimumHeight,canvasHeight),canvasHeight)
    val item=with(density){Offset(actualWidth.toPx(),actualHeight.toPx())}
    val default=with(density){PanelPosition(
        if(canvas.x>item.x)(defaultX.toPx()/(canvas.x-item.x)).coerceIn(0f,1f)else 0f,
        if(canvas.y>item.y)(defaultY.toPx()/(canvas.y-item.y)).coerceIn(0f,1f)else 0f)}
    val position=vm.layoutPositions[id] ?: default
    val offset=panelOffset(position,canvas.x,canvas.y,item.x,item.y)
    val move by rememberUpdatedState<(Offset)->Unit> { delta ->
        val current=vm.layoutPositions[id] ?: default
        vm.movePanel(id,movePanelItem(current,delta.x,delta.y,canvas.x,canvas.y,item.x,item.y))
    }
    Box(Modifier.offset{IntOffset(offset.x.roundToInt(),offset.y.roundToInt())}.size(actualWidth,actualHeight)
        .testTag("move-$id")
        .then(if(vm.layoutEditing)Modifier.border(2.dp,if(vm.selectedLayoutId==id)Accent else Line,RoundedCornerShape(8.dp)).clickable{vm.selectPanel(id)} else Modifier)
        .pointerInput(id,vm.layoutEditing,canvas,item) {
            if(vm.layoutEditing)detectDragGestures(onDragStart={vm.selectPanel(id)}) { change,delta -> change.consume();move(delta) }
        },contentAlignment=Alignment.Center) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderControl(title: String,value: Int,active: Boolean,config: SliderConfig,onChange: (Int)->Unit) {
    Column(Modifier.fillMaxSize().softCard().padding(horizontal=10.dp,vertical=4.dp)) {
        Row(Modifier.fillMaxWidth().height(20.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(title,fontSize=11.sp,fontWeight=FontWeight.Bold)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(config.stepped)value.toString() else "${value/10}",fontSize=12.sp,color=Accent,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold)
                TextButton({onChange(500)},enabled=active,contentPadding=PaddingValues(2.dp),modifier=Modifier.width(38.dp).height(20.dp)){Text(if(config.stepped)"中档" else "50%",fontSize=9.sp,color=Muted)}
            }
        }
        Slider(config.position(value),{onChange(it.roundToInt())},valueRange=0f..1000f,steps=if(config.stepped)config.values.size-2 else 0,enabled=active,modifier=Modifier.testTag(title).height(30.dp),
            track={ sliderState -> SliderDefaults.Track(sliderState,enabled=active,colors=SliderDefaults.colors(activeTickColor=Color.Transparent,inactiveTickColor=Color.Transparent),modifier=Modifier.drawWithContent {
                drawContent()
                if(config.stepped)repeat(config.values.size) { index ->
                    val x=(index.toFloat()/(config.values.size-1)*size.width).coerceIn(1.dp.toPx(),(size.width-1.dp.toPx()).coerceAtLeast(1.dp.toPx()))
                    drawLine(Color.White,Offset(x,size.height/2-3.dp.toPx()),Offset(x,size.height/2+3.dp.toPx()),strokeWidth=2.dp.toPx())
                }
            }) },
            thumb={ Canvas(Modifier.size(20.dp)) { drawCircle(Color.White);drawCircle(if(active)Accent else Muted,radius=size.width*.34f) } })
    }
}

@Composable
private fun StickControl(vm: RemoteViewModel,title: String,index: Int,x: Int,y: Int,active: Boolean,config: JoystickConfig,onMode: ()->Unit,onMove: (Int,Int)->Unit) {
    Column(Modifier.fillMaxSize().softCard().padding(10.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(title,fontSize=11.sp,fontWeight=FontWeight.Bold)
            Text("X $x  Y $y",fontSize=10.sp,color=Muted,fontFamily=FontFamily.Monospace)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical=5.dp).testTag("pad-zone-$index")) {
            val id=if(index==0)"leftPad" else "rightPad"
            val density=LocalDensity.current
            val scale=vm.layoutScales[id] ?: 1f
            val available=minOf(maxWidth,maxHeight)
            val side=(minOf(available,260.dp)*.75f*scale).coerceIn(minOf(available,80.dp),available)
            val canvas=with(density){Offset(maxWidth.toPx(),maxHeight.toPx())}
            val item=with(density){side.toPx()}
            val position=vm.layoutPositions[id] ?: PanelPosition(.5f,.5f)
            val offset=panelOffset(position,canvas.x,canvas.y,item,item)
            val move by rememberUpdatedState<(Offset)->Unit> { delta ->
                val current=vm.layoutPositions[id] ?: PanelPosition(.5f,.5f)
                vm.movePanel(id,movePanelItem(current,delta.x,delta.y,canvas.x,canvas.y,item,item))
            }
            Box(Modifier.offset{IntOffset(offset.x.roundToInt(),offset.y.roundToInt())}.size(side).testTag("move-$id")
                .then(if(vm.layoutEditing)Modifier.border(2.dp,Accent,RoundedCornerShape(22.dp)).clickable{vm.selectPanel(id)} else Modifier)
                .pointerInput(id,vm.layoutEditing,canvas,item) {
                    if(vm.layoutEditing)detectDragGestures(onDragStart={vm.selectPanel(id)}) { change,delta -> change.consume();move(delta) }
                }) { Joystick(title,x,y,active,config,onMove) }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("死区 ${config.deadZonePercent}% · ±${config.maxValue}",fontSize=9.sp,color=Muted)
            OutlinedButton(onMode,enabled=active,contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp),modifier=Modifier.height(32.dp).testTag("stick-mode-$index")) {
                Text(config.mode.title,fontSize=10.sp)
            }
        }
    }
}
