package io.github.norlive.remotecontrol

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.roundToInt
import kotlin.math.abs

internal class GamepadInput(context: Context,private val vm: RemoteViewModel) : InputManager.InputDeviceListener {
    private val manager=context.getSystemService(InputManager::class.java)
    private val buttons=GamepadButtons()
    private var deviceId: Int?=null
    private var running=false
    private var focused=true
    fun start() { running=true;manager.registerInputDeviceListener(this,null);refresh() }
    fun stop() { running=false;manager.unregisterInputDeviceListener(this);buttons.clear();vm.suspendGamepad() }
    fun focus(value: Boolean) { focused=value;if(!value){buttons.clear();if(deviceId!=null)vm.suspendGamepad()} }
    private fun isGamepad(device: InputDevice)=device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK)
    private fun refresh() {
        val devices=manager.inputDeviceIds.toList().mapNotNull(manager::getInputDevice).filter(::isGamepad)
        val selected=devices.firstOrNull { it.id==deviceId }
        if(selected?.id!=deviceId) { buttons.clear();vm.suspendGamepad() }
        deviceId=selected?.id;vm.gamepadDevice(selected?.name)
    }
    override fun onInputDeviceAdded(id: Int) { refresh() }
    override fun onInputDeviceChanged(id: Int) { refresh() }
    override fun onInputDeviceRemoved(id: Int) {
        if(id==deviceId) { buttons.clear();deviceId=null;vm.suspendGamepad();vm.gamepadDevice(null) }
        refresh()
    }
    private fun select(id: Int,device: InputDevice?,takeOver: Boolean): Boolean {
        if(!running || !focused || vm.layoutEditing)return false
        if(deviceId!=id) {
            if(!takeOver)return false
            buttons.clear()
            if(deviceId!=null)vm.suspendGamepad()
        }
        deviceId=id;vm.gamepadDevice(device?.name ?: "原生安卓手柄")
        return true
    }
    fun key(event: KeyEvent): Boolean {
        if(!event.isFromSource(InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(InputDevice.SOURCE_JOYSTICK))return false
        val button=when(event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_X -> GamepadButton.X;KeyEvent.KEYCODE_BUTTON_Y -> GamepadButton.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> GamepadButton.L1;KeyEvent.KEYCODE_BUTTON_R1 -> GamepadButton.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> GamepadButton.L2;KeyEvent.KEYCODE_BUTTON_R2 -> GamepadButton.R2
            else -> return false
        }
        if(!select(event.deviceId,event.device,event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0))return false
        vm.gamepadEvent("id=${event.deviceId} source=0x${event.source.toString(16)} key=${event.keyCode} action=${event.action}")
        val device=event.device
        val triggerAxes=when(button) {
            GamepadButton.L2 -> listOf(MotionEvent.AXIS_LTRIGGER,MotionEvent.AXIS_BRAKE)
            GamepadButton.R2 -> listOf(MotionEvent.AXIS_RTRIGGER,MotionEvent.AXIS_GAS)
            else -> emptyList()
        }
        val analog=triggerAxes.any { device?.getMotionRange(it)!=null }
        if(event.action==KeyEvent.ACTION_UP)buttons.key(button,false,analog)
        else if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 && buttons.key(button,true,analog))vm.gamepadPress(button)
        return true
    }
    fun motion(event: MotionEvent): Boolean {
        if(!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked!=MotionEvent.ACTION_MOVE)return false
        fun axis(preferred: Int,fallback: Int): Float {
            val device=event.device
            val selected=if(device?.getMotionRange(preferred)!=null || device?.getMotionRange(fallback)==null)preferred else fallback
            return event.getAxisValue(selected)
        }
        fun triggerValue(preferred: Int,fallback: Int): Float {
            val value=axis(preferred,fallback)
            val range=event.device?.getMotionRange(preferred) ?: event.device?.getMotionRange(fallback)
            return (if(range!=null && range.min<0 && range.range>0)(value-range.min)/range.range else value).coerceIn(0f,1f)
        }
        val lx=event.getAxisValue(MotionEvent.AXIS_X);val ly=event.getAxisValue(MotionEvent.AXIS_Y)
        val rx=axis(MotionEvent.AXIS_Z,MotionEvent.AXIS_RX);val ry=axis(MotionEvent.AXIS_RZ,MotionEvent.AXIS_RY)
        val lt=triggerValue(MotionEvent.AXIS_LTRIGGER,MotionEvent.AXIS_BRAKE)
        val rt=triggerValue(MotionEvent.AXIS_RTRIGGER,MotionEvent.AXIS_GAS)
        vm.gamepadEvent("id=${event.deviceId} source=0x${event.source.toString(16)} X=$lx Y=$ly RX=$rx RY=$ry L2=$lt R2=$rt")
        if(!select(event.deviceId,event.device,listOf(lx,ly,rx,ry).any { abs(it)>.05f } || lt>=.6f || rt>=.6f))return false
        vm.axes(true,(lx*1000).roundToInt(),(-ly*1000).roundToInt())
        vm.axes(false,(rx*1000).roundToInt(),(-ry*1000).roundToInt())
        if(buttons.trigger(GamepadButton.L2,lt))vm.gamepadPress(GamepadButton.L2)
        if(buttons.trigger(GamepadButton.R2,rt))vm.gamepadPress(GamepadButton.R2)
        return true
    }
}
